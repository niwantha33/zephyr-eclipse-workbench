# Pinned Espressif SVD integrity (SEC-016)

The automated Espressif Register Explorer downloader only trusts the exact
vendor SVD bytes from immutable Git commit `be20aa12560889d6125d144cdb48cf615ac17628` in
[espressif/svd](https://github.com/espressif/svd/tree/be20aa12560889d6125d144cdb48cf615ac17628/svd).

The eight SHA-256 values are embedded in `GitHubSvdProvisioner`. They were
derived directly from GitHub's Git blob contents at that full commit, and the
observed byte lengths were checked against the vendor repository tree.

The downloader verifies SHA-256 for **cached and freshly downloaded** files
before permitting device-identity SVD parsing. Correct `<name>` XML alone
never authorises trust. Unknown parts, tampered content, partial downloads and
unapproved redirects fail closed. Replacing an upstream SVD requires a review
of the new immutable revision, per-file digest, parser compatibility and
Register Explorer behavior.

This protects the Espressif automatic-download path. Other local user-imported
SVD files are user-supplied project data and are not vendor-authenticated by
this manifest. TI CMSIS pack acquisition is tracked separately under SEC-018;
do not treat this pin set as a trust anchor for that path.
