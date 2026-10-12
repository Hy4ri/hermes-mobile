client-identities.p12 contains three PUBLIC TEST FIXTURE private keys (password: test-only).
They are self-signed, have no real-world credentials, and are never packaged in the app.
Generated with JDK 21 keytool: RSA 2048, start date 2020-01-01, validity 36500 days.
server: CN=localhost, SAN DNS localhost + IP 127.0.0.1, EKU serverAuth.
first / second: CN=first-client / second-client, EKU clientAuth.
Tests use standard JSSE plus the existing MockWebServer dependency.

server-trust.p12 also contains PUBLIC TEST FIXTURE keys (password: test-only).
RSA 2048 / SHA-256 chains with separate system, user and unknown test CAs;
these names are injected JVM trust stores, NOT Android's actual certificate store.
system/user/unknown leaves: SAN localhost + 127.0.0.1, EKU serverAuth,
valid 2020-01-01 through 2120-01-01. expired leaf: 2000-01-01 through 2001-01-01.
CA roots are stored as system-ca/user-ca/unknown-ca. CA BasicConstraints and
keyCertSign/cRLSign are set; leaves have BasicConstraints CA=false.
Generated using Python cryptography 50's x509 + PKCS12 serialization, merged
with JDK 21 keytool -importkeystore; roots added with keytool -importcert.
All keys are test-only and are never packaged in the app.
