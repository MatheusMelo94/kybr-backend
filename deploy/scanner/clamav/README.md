# Scanner configuration (ClamAV)

The isolated scanner app (`deploy/scanner/Dockerfile`) checks every uploaded document before the API
accepts it. This directory holds its ClamAV configuration:

- `clamd.conf`: the scanner daemon's settings. It fails closed: a file that can't be scanned completely
  is refused, never passed.
- `kybr.ndb` and `kybr.ldb`: Kybr's own signatures, added to ClamAV's databases. They include the
  self-test marker `KYBR-SCANNER-SELFTEST-5E1F7D3C`, which the stub scanner also flags, so the whole
  scanning path can be proven with a harmless file in any environment.

The daemon is reachable only through the scanner app's mutual-TLS link; it never listens on a public
interface.

## Running the real-ClamAV test

The opt-in integration test copies these files into a real ClamAV container:

```sh
./mvnw verify -Dkybr.it.clamav=true -Dkybr.it.clamav.config-dir=deploy/scanner/clamav \
  -Dkybr.it.clamav.image=clamav/clamav:1.5.4_base -Dit.test=ClamdScannerClamavIT
```

CI's "Scanner" workflow runs it, plus an image smoke test with generated sample files
(`scripts/documents/make-test-files.py`).

## Updating

- **Virus databases:** `freshclam` refreshes them at start and on its schedule; the API refuses to
  accept files while the databases are too old.
- **Custom signatures or settings:** edit the files here, run the test above, and deploy the scanner
  app through its own workflow. The scanner machine checks the custom signature files for changes
  while it runs and restarts if they differ from the shipped copies.
- **ClamAV image:** bump the pinned version and digest in the Dockerfile and in the test command, then
  run the test above.
