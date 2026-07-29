#!/usr/bin/env python3
"""Generate QWeather JWT token for verification"""
import time
import base64
import hashlib
import struct

# QWeather credentials
PRIVATE_KEY_PEM = """-----BEGIN PRIVATE KEY-----
MC4CAQAwBQYDK2VwBCIEICwOvrfAlLBDEnFi+yhRLmCql0P1oXEgu7Jb2akwAQmJ
-----END PRIVATE KEY-----"""

PROJECT_ID = "2B89AN9KXV"
KID = "CAPR2BDUDV"

def base64url_encode(data):
    """Base64URL encode without padding"""
    if isinstance(data, str):
        data = data.encode('utf-8')
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode('utf-8')

def generate_jwt():
    """Generate JWT token"""
    # Header
    header = {
        "alg": "EdDSA",
        "kid": KID
    }
    header_json = '{"alg":"EdDSA","kid":"%s"}' % KID
    header_encoded = base64url_encode(header_json)
    
    # Payload
    now = int(time.time())
    iat = now - 30  # 30 seconds in the past
    exp = iat + 900  # 15 minutes validity
    
    payload_json = '{"sub":"%s","iat":%d,"exp":%d}' % (PROJECT_ID, iat, exp)
    payload_encoded = base64url_encode(payload_json)
    
    # Data to sign
    data = header_encoded + "." + payload_encoded
    
    print("=" * 60)
    print("QWeather JWT Token Generator")
    print("=" * 60)
    print()
    print("Configuration:")
    print(f"  Project ID: {PROJECT_ID}")
    print(f"  KID: {KID}")
    print(f"  IAT: {iat}")
    print(f"  EXP: {exp}")
    print(f"  Valid for: {(exp - iat) // 60} minutes")
    print()
    print("Header (decoded):")
    print(f"  {header_json}")
    print()
    print("Payload (decoded):")
    print(f"  {payload_json}")
    print()
    print("Header (base64url):")
    print(f"  {header_encoded}")
    print()
    print("Payload (base64url):")
    print(f"  {payload_encoded}")
    print()
    print("=" * 60)
    print("Data to sign (header.payload):")
    print(f"  {data}")
    print("=" * 60)
    print()
    print("NOTE: To complete the JWT, you need to sign the data above")
    print("with your Ed25519 private key using Base64URL encoding.")
    print()
    print("You can verify this at: https://jwt.qweather.com")
    print("=" * 60)
    
    return data

if __name__ == "__main__":
    generate_jwt()
