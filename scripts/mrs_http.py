#!/usr/bin/env python3
"""Small TLS-verifying MRS client for reproducible spike/integration experiments."""
import base64
import hashlib
import hmac
import json
import os
import secrets
import ssl
import urllib.error
import urllib.request
from pathlib import Path


class Mrs:
    def __init__(self, base=None, ca=None):
        self.base = base or os.getenv("MRS_URL", "https://localhost:8443/orders")
        self.context = ssl.create_default_context(cafile=str(ca or os.getenv("MRS_CA") or Path(__file__).resolve().parents[1] / ".mrs/tls/server.crt"))
        self.token = None

    def request(self, method, path, body=None):
        headers = {"Accept": "application/json", "Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(self.base + path, method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
        try:
            response = urllib.request.urlopen(request, context=self.context, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            try:
                result = json.loads(raw) if raw else None
            except json.JSONDecodeError:
                result = raw.decode()
            return response.status, result

    def login(self, username, password):
        nonce = secrets.token_hex(20)
        status, challenge = self.request("POST", "/authentication/login", {
            "authApp": "OrderMRS", "username": username, "nonce": nonce, "sessionType": "bearer"})
        if status != 200:
            raise RuntimeError(f"Login challenge failed: HTTP {status}")
        salt = bytes(challenge["salt"])
        server_nonce = challenge["nonce"]
        assert server_nonce.startswith(nonce)
        message = f"n={username},r={nonce},r={server_nonce},s={base64.b64encode(salt).decode()},i={challenge['iterations']},r={server_nonce}"
        salted = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, challenge["iterations"])
        client_key = hmac.digest(salted, b"Client Key", "sha256")
        signature = hmac.digest(hashlib.sha256(client_key).digest(), message.encode(), "sha256")
        status, result = self.request("POST", "/authentication/login", {
            "clientProof": list(a ^ b for a, b in zip(client_key, signature)),
            "nonce": server_nonce, "state": "response"})
        if status != 200:
            raise RuntimeError(f"Login proof failed: HTTP {status}")
        self.token = result["accessToken"]
        return self
