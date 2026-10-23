# Workbench updates: first private development release

The workbench can now check its private GitHub update channel, download and verify
the selected release, and open Eclipse's normal installation wizard. This updates
our plug-ins only. Tools installation and the project wizard are later slices.

## One-time setup

1. Install `Zephyr_Eclipse_Workbench_0.1.1_bootstrap.zip` through **Help > Install
   New Software > Add > Archive**. Select Zephyr Eclipse Workbench, complete the
   wizard and restart Eclipse. Version 0.1.0 has no updater, so this final manual
   installation is necessary.
2. Choose **Zephyr > Configure Workbench Updates**.
3. While this repository is private, create a GitHub fine-grained personal access
   token restricted to `niwantha33/zephyr-eclipse-workbench`, with repository
   **Contents: Read-only** permission. Paste it into the password field in Eclipse;
   never put it in chat, source, a project file or a URL. Eclipse stores it using
   encrypted secure storage and may ask for a secure-storage password.
4. Keep automatic checks enabled and save. Choose **Zephyr > Check for Workbench
   Updates** to test the connection immediately.
5. The initial channel offers 0.1.2. Click **Install Update**, follow the native
   Eclipse licence/trust wizard and restart when prompted. Future releases use
   the same connection; no repeated archive selection is needed.

Development packages are unsigned. Eclipse may display its unsigned-content
warning. Signed release infrastructure is still pending. Do not disable Eclipse
trust verification globally. If a token expires or is revoked, open Configure
Workbench Updates and replace it. The public-access option is only for a future
public repository; it does not make this repository public.

## Behaviour and boundaries

- Checks run about 20 seconds after startup and every six hours while Eclipse is
  open, once the connection is configured. Manual checks are always available.
- New versions produce an Install Update / Later notification. Automatic checks
  do not install or restart Eclipse silently.
- Builds and debug sessions postpone installation. Eclipse owns installation,
  licence acceptance, trust decisions, progress and the restart request.
- Downloads use authenticated HTTPS without redirects, immutable Git commit
  references, bounded file sizes and per-file SHA-256 checks. Digests detect
  corruption; publisher identity depends on the authenticated repository and its
  access controls until signing is introduced.
- Failed or cancelled partial downloads are removed. Verified repositories remain
  cached for provisioning and installation history. p2 history is not a backup
  of applications or external tools.
- Zephyr source, SDK selection, Python environment and application files are not
  upgraded by this workbench channel.

## Release publication

The private `updates` branch contains `releases/<qualified-version>/` and
`channel.properties`. It has no GitHub Pages endpoint. Both source and release
artifacts remain private.

1. Bump all Maven, bundle and feature versions together. Run `scripts/test_core.py`,
   Maven `clean verify`, and `scripts/verify_site.py` using Java 21.
2. Retain the preceding release site. Run `scripts/headless_p2_smoke.py` against
   a disposable copy of Eclipse, passing the old and new sites in order.
3. Use `scripts/prepare_update.py --revision <40-hex-artifact-commit> --output
   <new-directory>` to generate the bounded manifest. Release files must be
   committed first; then regenerate the manifest using that immutable commit.
4. Publish `channel.properties` in a second commit on `updates`. Never overwrite
   an existing version's artifacts. Preserve old releases for recovery.
5. Verify the remote manifest, file digests and private repository visibility.
   Test desktop notification, secure storage, wizard, restart and rollback on
   supported hosts before calling a release stable.

The 56 automated core/transport/staging checks and package validation do not prove
the Windows desktop flow. Headless p2 tests prove provisioning only; account
authentication and interactive trust prompts still require desktop validation.
