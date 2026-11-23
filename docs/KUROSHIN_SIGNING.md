# Kuroshin APK signing key

This repository is an independent distribution and uses its own signing identity.
Never reuse or expect the upstream author's private signing key.

## 1. Generate the keystore once

Use a trusted computer with a JDK installed. Run the following command in a private working directory:

```bash
keytool -genkeypair -v \
  -keystore kuroshinkey.jks \
  -storetype JKS \
  -alias kuroshinkey \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Choose a strong, unique keystore password when prompted. Keep the keystore file and password in a secure backup. Do not commit either to Git, upload them to public file hosting, or paste them into chat.

The key alias must remain exactly `kuroshinkey`. The Actions workflow uses the same password for the JKS store and key.

## 2. Convert the keystore to Base64

On Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\kuroshinkey.jks")) | Set-Clipboard
```

On Linux:

```bash
base64 -w 0 kuroshinkey.jks
```

Copy the complete Base64 output. Do not add line breaks or whitespace when entering the secret.

## 3. Add GitHub Actions secrets

In the repository, open **Settings → Secrets and variables → Actions → New repository secret** and create exactly these two secrets:

- `KUROSHIN_KEYSTORE_BASE64`: the complete Base64 text from step 2.
- `KUROSHIN_KEYSTORE_PASSWORD`: the password chosen in step 1.

The key alias is fixed in the build configuration and must be `kuroshinkey`; no alias secret is required.

## 4. Important

- Keep an offline backup of `kuroshinkey.jks` and its password. GitHub Secrets are not a replacement for your backup.
- Every future APK in this distribution must use this exact keystore. Losing or replacing it prevents in-place updates over APKs signed with this key.
- An APK signed with this new key cannot update an already-installed copy signed with a different key. The old module may need to be uninstalled before installing the first Kuroshin-signed APK.
- Never expose the Base64 value or password in issues, pull requests, commits, workflow logs, or chat.
