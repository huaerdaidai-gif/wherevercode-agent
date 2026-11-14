import base64
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from scripts.release import android_release as release


class ReleaseSafetyTests(unittest.TestCase):
    properties = 'versionCode=183\nminimumUpgradeVersionCode=183\n'
    cert = 'a' * 64
    source = 'b' * 40

    def test_counter_exceeds_installed_and_protected_published_versions(self):
        self.assertEqual(183, release.version_counter('183', self.properties, '182'))
        for value, published in [('182', '182'), ('183', '183'), ('7', '5'), ('1e3', '182'), ('2100000001', '182'), ('183', None)]:
            with self.assertRaises(ValueError):
                release.version_counter(value, self.properties, published)

    def test_full_fingerprint_required_not_legacy_eight_digit_prefix(self):
        self.assertEqual(self.cert, release.certificate_digest(':'.join(['AA'] * 32)))
        for value in ['', 'ee743f03', 'not-a-certificate']:
            with self.assertRaises(ValueError):
                release.certificate_digest(value)

    def test_wrapped_urlsafe_keystore_is_private_and_never_overwrites_existing_file(self):
        data = b'\xfb\xffpublic-fixture'
        encoded = base64.urlsafe_b64encode(data).decode().rstrip('=')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'keystore'
            release.restore_keystore(' \r\n' + encoded + '\n', path)
            self.assertEqual(data, path.read_bytes())
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                release.restore_keystore(encoded, path)

    def test_corrupt_keystore_error_never_echoes_material(self):
        material = 'PRIVATE-not-base64!'
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError) as raised:
                release.restore_keystore(material, Path(directory) / 'keystore')
            self.assertNotIn(material, str(raised.exception))

    def test_keystore_certificate_must_match_full_protected_digest(self):
        fixture = b'public DER certificate fixture'
        with patch.dict(os.environ, {'ANDROID_KEYSTORE_PASSWORD': 'fixture', 'ANDROID_KEY_ALIAS': 'fixture', 'ANDROID_KEY_PASSWORD': 'fixture'}):
            execute = lambda args: subprocess.CompletedProcess(args, 0, fixture, b'')
            release.check_keystore('fixture', hashlib.sha256(fixture).hexdigest(), execute)
            with self.assertRaises(ValueError):
                release.check_keystore('fixture', self.cert, execute)

    def test_update_source_verification_checks_actual_generated_config(self):
        text = 'String UPDATE_DRIVE_FILE_ID = "fixture-id"; boolean MAINTAINER_SIGNED = true;'
        release.check_build_config(text, 'fixture-id')
        with self.assertRaises(ValueError) as raised:
            release.check_build_config(text, 'other-sensitive-fixture')
        self.assertNotIn('other-sensitive-fixture', str(raised.exception))
        with self.assertRaises(ValueError):
            release.check_build_config(text.replace('true', 'false'), 'fixture-id')

    def test_signature_failure_cannot_be_hidden_by_successful_badging(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'fixture.apk'
            apk.write_bytes(b'fixture')
            def execute(args):
                return subprocess.CompletedProcess(args, 1, b'fake verification output', b'')
            with self.assertRaisesRegex(ValueError, 'cryptographic'):
                release.check_apk(apk, 'apksigner', 'aapt', self.cert, 183, self.source, '', '', execute)

    def test_production_identity_counter_source_and_certificate_are_all_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'fixture.apk'
            apk.write_bytes(b'fixture')
            badging = f"package: name='wherevercode.agent' versionCode='183' versionName='0.4.0-native.183+{self.source[:8]}.20261114'"
            cert_text = f'Signer #1 certificate SHA-256 digest: {self.cert}\n'
            def execute(args):
                text = cert_text if args[0] == 'apksigner' else badging
                # Badging is usable despite the adaptive-icon tool warning.
                return subprocess.CompletedProcess(args, 0 if args[0] == 'apksigner' else 1, text.encode(), b'')
            config = 'String UPDATE_DRIVE_FILE_ID = ""; boolean MAINTAINER_SIGNED = true;'
            result = release.check_apk(apk, 'apksigner', 'aapt', self.cert, 183, self.source, config, '', execute)
            self.assertFalse(result['physicalUpgradeVerified'])
            for replacement in [badging.replace('wherevercode.agent', 'wherevercode.agent.ci'), badging.replace("versionCode='183'", "versionCode='7'"), badging.replace(self.source[:8], 'cccccccc')]:
                old = badging
                badging = replacement
                with self.assertRaises(ValueError):
                    release.check_apk(apk, 'apksigner', 'aapt', self.cert, 183, self.source, config, '', execute)
                badging = old
            cert_text = cert_text.replace(self.cert, 'c' * 64)
            with self.assertRaises(ValueError):
                release.check_apk(apk, 'apksigner', 'aapt', self.cert, 183, self.source, config, '', execute)


if __name__ == '__main__':
    unittest.main()
