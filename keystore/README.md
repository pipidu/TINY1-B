# TINY1-B project signing key

This keystore is committed **so later GitHub Release APKs can overwrite the same installed app**. It is a project update key, not a high-security production secret.

| Field | Value |
|-------|--------|
| File | `keystore/tiny1b-release.jks` |
| Alias | `tiny1b` |
| Store password | `tiny1b` |
| Key password | `tiny1b` |
| Algorithm | RSA 2048 |
| Application id | `com.pipidu.tiny1b` |

Debug and release both use this config (no `.debug` applicationId suffix).
