#!/usr/bin/env python3
import time
import base64
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.hazmat.primitives.serialization import load_pem_private_key

# Load private key
private_key_pem = b"""-----BEGIN PRIVATE KEY-----
MC4CAQAwBQYDK2VwBCIEICwOvrfAlLBDEnFi+yhRLmCql0P1oXEgu7Jb2akwAQmJ
-----END PRIVATE KEY-----"""

private_key = load_pem_private_key(private_key_pem, password=None)

# Generate JWT
now = int(time.time())
iat = now - 30
exp = iat + 900

header = '{"alg":"EdDSA","kid":"CAPR2BDUDV"}'
payload = '{"sub":"2B89AN9KXV","iat":%d,"exp":%d}' % (iat, exp)

def base64url_encode(data):
    if isinstance(data, str):
        data = data.encode('utf-8')
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode('utf-8')

header_encoded = base64url_encode(header)
payload_encoded = base64url_encode(payload)
data = header_encoded + '.' + payload_encoded

# Sign
signature = private_key.sign(data.encode('utf-8'))
signature_encoded = base64url_encode(signature)

jwt = data + '.' + signature_encoded

print('=' * 60)
print('QWeather JWT Token (Complete)')
print('=' * 60)
print()
print('Header:', header)
print('Payload:', payload)
print()
print('Token:')
print(jwt)
print()
print('=' * 60)
print('Verify at: https://jwt.qweather.com')
print('=' * 60)
