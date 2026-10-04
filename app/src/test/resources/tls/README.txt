client-identities.p12 contains three PUBLIC TEST FIXTURE private keys (password: test-only).
They are self-signed, have no real-world credentials, and are never packaged in the app.
Generated with JDK 21 keytool: RSA 2048, start date 2020-01-01, validity 36500 days.
server: CN=localhost, SAN DNS localhost + IP 127.0.0.1, EKU serverAuth.
first / second: CN=first-client / second-client, EKU clientAuth.
Tests use standard JSSE plus the existing MockWebServer dependency.
