#!/usr/bin/env python3
"""
Builds the release signing keystore from one secret password, the same way
every time: the same password always gives the same RSA key and certificate,
so every build signs with an identical key and phones accept it as an update.

The key never has to be stored or copied anywhere: CI derives it from the
SIGNING_SEED repository secret on each run. Keep that secret; changing or
losing it means a new key (phones would then refuse to update).

Usage: derive_keystore.py OUT.p12 STORE_PASSWORD   (seed read from $SIGNING_SEED)
"""
import datetime
import hashlib
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

ALIAS = "helmetmedia"
KEY_BITS = 3072
E = 65537


class Stream:
    """Deterministic byte stream: SHAKE-256 over a stretched seed and a counter."""

    def __init__(self, seed: bytes):
        self.key = seed
        self.counter = 0

    def bytes(self, n: int) -> bytes:
        out = hashlib.shake_256(self.key + self.counter.to_bytes(8, "big")).digest(n)
        self.counter += 1
        return out

    def int_below(self, n: int) -> int:
        while True:
            v = int.from_bytes(self.bytes((n.bit_length() + 7) // 8 + 8), "big") % n
            if v > 1:
                return v


def is_probable_prime(n: int, s: Stream, rounds: int = 64) -> bool:
    if n < 2:
        return False
    for p in (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37):
        if n % p == 0:
            return n == p
    d, r = n - 1, 0
    while d % 2 == 0:
        d //= 2
        r += 1
    for _ in range(rounds):
        x = pow(s.int_below(n - 2), d, n)
        if x in (1, n - 1):
            continue
        for _ in range(r - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def prime(bits: int, s: Stream) -> int:
    while True:
        c = int.from_bytes(s.bytes(bits // 8), "big")
        c |= (1 << (bits - 1)) | (1 << (bits - 2)) | 1  # full length, odd
        if (c - 1) % E != 0 and is_probable_prime(c, s):
            return c


def derive_key(seed_text: str) -> rsa.RSAPrivateKey:
    if len(seed_text) < 12:
        raise SystemExit("SIGNING_SEED is too short: use at least 12 characters")
    stretched = hashlib.pbkdf2_hmac("sha256", seed_text.encode(), b"helmet-media-signing-v1", 600_000, 64)
    s = Stream(stretched)
    while True:
        p = prime(KEY_BITS // 2, s)
        q = prime(KEY_BITS // 2, s)
        if p == q:
            continue
        if p < q:
            p, q = q, p
        n = p * q
        if n.bit_length() != KEY_BITS:
            continue
        phi = (p - 1) * (q - 1)
        d = pow(E, -1, phi)
        nums = rsa.RSAPrivateNumbers(
            p=p, q=q, d=d,
            dmp1=d % (p - 1), dmq1=d % (q - 1), iqmp=pow(q, -1, p),
            public_numbers=rsa.RSAPublicNumbers(E, n),
        )
        return nums.private_key()


def certificate(key: rsa.RSAPrivateKey) -> x509.Certificate:
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Helmet Media")])
    # Fixed fields and a deterministic (PKCS#1 v1.5) signature: the same key gives the same certificate.
    return (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(int.from_bytes(hashlib.sha256(b"helmet-media-cert").digest()[:8], "big"))
        .not_valid_before(datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc))
        .not_valid_after(datetime.datetime(2076, 1, 1, tzinfo=datetime.timezone.utc))
        .sign(key, hashes.SHA256())
    )


def main() -> None:
    out, store_password = sys.argv[1], sys.argv[2]
    seed = os.environ.get("SIGNING_SEED", "")
    key = derive_key(seed)
    cert = certificate(key)
    data = pkcs12.serialize_key_and_certificates(
        ALIAS.encode(), key, cert, None,
        serialization.BestAvailableEncryption(store_password.encode()),
    )
    with open(out, "wb") as f:
        f.write(data)
    print("certificate SHA-256:", cert.fingerprint(hashes.SHA256()).hex(":").upper())


if __name__ == "__main__":
    main()
