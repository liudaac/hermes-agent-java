import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "cleanup-test-tenants.py"
spec = importlib.util.spec_from_file_location("cleanup_test_tenants", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class CleanupTestTenantsTest(unittest.TestCase):
    def test_is_test_tenant_allows_known_prefixes(self):
        self.assertTrue(mod.is_test_tenant("browser-bridge-test-123"))
        self.assertTrue(mod.is_test_tenant("soak-tenant-abc"))
        self.assertTrue(mod.is_test_tenant("tenant-api-test-uuid"))
        self.assertTrue(mod.is_test_tenant("agent-tenant-1-run"))

    def test_is_test_tenant_protects_default_and_unknown_names(self):
        self.assertFalse(mod.is_test_tenant("default"))
        self.assertFalse(mod.is_test_tenant("production"))
        self.assertFalse(mod.is_test_tenant("customer-acme"))
        self.assertFalse(mod.is_test_tenant("personal"))

    def test_discover_only_returns_matching_tenants(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tenants = root / "tenants"
            tenants.mkdir()
            for name in ["default", "browser-approval-1", "customer-acme", "soak-tenant-1"]:
                d = tenants / name
                d.mkdir()
                (d / "marker.txt").write_text(name)

            found = mod.discover(root)
            ids = {item["tenant_id"] for item in found}
            self.assertEqual({"browser-approval-1", "soak-tenant-1"}, ids)
            self.assertTrue(all(item["bytes"] > 0 for item in found))


if __name__ == "__main__":
    unittest.main()
