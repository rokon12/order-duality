#!/usr/bin/env python3
"""Live MRS boundary tests. Uses disposable fixture orders in the dedicated Compose database."""
import concurrent.futures
import json
import os
from pathlib import Path
import secrets
import subprocess
import unittest
import uuid
from mrs_http import Mrs

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = []


def sql(statement, check=True):
    return subprocess.run([
        "docker", "compose", "-f", "compose.mrs.yml", "exec", "-T",
        "-e", "MYSQL_PWD=local-mrs-root-only", "mysql", "mysql", "-uroot", "-Dorder_demo", "-Nse", statement
    ], cwd=ROOT, text=True, capture_output=True, check=check)


class BoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        assert sql("SELECT version FROM order_demo.mrs_demo_setup").stdout.strip() == "1"
        cls.clients = {user: Mrs(base=os.getenv("MRS_URL", "https://localhost:8443/orders")).login(user, user + "-Local!26")
                       for user in ("alice", "bob", "reader", "executor")}
        cls.alice_id = cls.operation("alice", "whoami", {})["principalId"]
        cls.bob_id = cls.operation("bob", "whoami", {})["principalId"]

    def setUp(self):
        self.order = 9_000_000_000 + secrets.randbelow(1_000_000_000)
        sql(f"INSERT INTO order_demo.orders(id,customer_id,status) VALUES ({self.order},42,'PROCESSING')")

    def tearDown(self):
        sql(f"DELETE a FROM order_demo.action_audit a JOIN order_demo.cancellation_requests r ON a.request_id=r.id WHERE r.order_id={self.order}; "
            f"DELETE FROM order_demo.cancellation_requests WHERE order_id={self.order}; DELETE FROM order_demo.orders WHERE id={self.order};")

    @classmethod
    def operation(cls, user, name, body):
        status, envelope = cls.clients[user].request("POST", "/shop/" + name, body)
        assert status == 200, (name, status, envelope)
        return envelope["resultSets"][0]["items"][0]["result"]

    def request(self, **extra):
        return self.operation("alice", "requestCancellation", {"orderId": self.order,
            "reason": "Test cancellation", "requestKey": str(uuid.uuid4()), **extra})

    def confirm(self, request_id, **extra):
        return self.operation("executor", "confirmCancellation", {
            "requestId": request_id, "ownerHex": self.alice_id, **extra})

    def status(self):
        return sql(f"SELECT status FROM order_demo.orders WHERE id={self.order}").stdout.strip()

    def record(self, label, result):
        EVIDENCE.append({"test": self.id().split(".")[-1], "check": label, "result": result})

    def test_authentication_and_roles(self):
        anonymous = Mrs()
        self.assertEqual(401, anonymous.request("GET", "/shop/orders")[0])
        anonymous.token = "tampered-token"
        self.assertEqual(401, anonymous.request("GET", "/shop/orders")[0])
        with self.assertRaises(RuntimeError):
            Mrs().login("alice", "wrong-password")
        body = {"orderId": self.order, "reason": "Denied", "requestKey": str(uuid.uuid4())}
        self.assertEqual(403, self.clients["reader"].request("POST", "/shop/requestCancellation", body)[0])
        self.assertEqual(403, self.clients["alice"].request("POST", "/shop/confirmCancellation", {
            "requestId": str(uuid.uuid4()), "ownerHex": self.alice_id})[0])
        self.assertEqual(403, self.clients["executor"].request("GET", "/shop/orders")[0])
        self.record("unauthenticated=401; reader write, agent confirm, executor read=403", "PASS")

    def test_ownership_and_forged_identity(self):
        self.assertEqual(404, self.clients["bob"].request("GET", f"/shop/orders/{self.order}")[0])
        result = self.operation("bob", "getOrder", {"orderId": self.order, "userId": self.alice_id})
        self.assertEqual({"ok": False, "code": "ORDER_UNAVAILABLE"}, result)
        who = self.operation("bob", "whoami", {"userId": self.alice_id})
        self.assertEqual(self.bob_id, who["principalId"])
        result = self.operation("bob", "requestCancellation", {"orderId": self.order,
            "userId": self.alice_id, "reason": "Forged owner", "requestKey": str(uuid.uuid4())})
        self.assertEqual("ORDER_UNAVAILABLE", result["code"])
        self.assertEqual("PROCESSING", self.status())
        self.record("forged userId is overwritten; foreign reads and writes denied", "PASS")

    def test_native_document_and_wrapper_limitation(self):
        document = self.operation("alice", "getOrder", {"orderId": 1001})["document"]
        self.assertEqual(1001, document["_id"])
        self.assertTrue(document["_metadata"]["etag"])
        self.assertEqual(2, len(document["items"]))
        failed = sql("CREATE VIEW order_demo.spike_unsupported_wrapper AS SELECT data FROM order_demo.orders_dv", check=False)
        if failed.returncode == 0:
            sql("DROP VIEW order_demo.spike_unsupported_wrapper")
        self.assertNotEqual(0, failed.returncode)
        self.assertIn("6504", failed.stderr)
        self.record("native document via checked procedure; SQL wrapper rejected", {"documentId": document["_id"], "wrapperError": 6504})

    def test_no_generic_writes_or_unrelated_resources(self):
        for method in ("PUT", "DELETE"):
            status, _ = self.clients["alice"].request(method, f"/shop/orders/{self.order}", {"status": "SHIPPED", "price": 0})
            self.assertIn(status, (403, 405))
        self.assertEqual(404, self.clients["alice"].request("GET", "/shop/products")[0])
        self.assertEqual(404, self.clients["alice"].request("GET", "/shop/action_audit")[0])
        self.assertEqual("PROCESSING", self.status())
        self.record("generic mutations and unexposed resources unavailable", "PASS")

    def test_request_does_not_cancel_and_confirmation_is_bound(self):
        request = self.request()
        self.assertTrue(request["ok"])
        self.assertEqual("PENDING", request["state"])
        self.assertEqual("PROCESSING", self.status())
        self.assertEqual(404, self.clients["bob"].request("GET", "/shop/requests/" + request["requestId"])[0])
        self.assertEqual("REQUEST_UNAVAILABLE", self.confirm(request["requestId"], ownerHex=self.bob_id)["code"])
        confirmed = self.confirm(request["requestId"])
        self.assertEqual("CONFIRMED", confirmed["state"])
        self.assertEqual("CANCELLED", self.status())
        self.assertEqual(confirmed, self.confirm(request["requestId"]))
        count = sql(f"SELECT COUNT(*) FROM order_demo.action_audit WHERE request_id='{request['requestId']}' AND event='CANCELLED'").stdout.strip()
        self.assertEqual("1", count)
        self.record("request/confirmation/replay", {"request": request, "confirmation": confirmed, "cancellationAuditRows": 1})

    def test_retry_and_conflicting_payload(self):
        key = str(uuid.uuid4())
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: self.request(requestKey=key), range(2)))
        self.assertEqual(results[0], results[1])
        self.assertEqual("IDEMPOTENCY_CONFLICT", self.request(requestKey=key, reason="Different reason")["code"])
        self.record("concurrent request retries create one request", "PASS")

    def test_shipping_after_request_is_rechecked(self):
        request = self.request()
        sql(f"UPDATE order_demo.orders SET status='SHIPPED' WHERE id={self.order}")
        self.assertEqual("ORDER_NOT_CANCELLABLE", self.confirm(request["requestId"])["code"])
        self.assertEqual("SHIPPED", self.status())
        self.assertEqual("ORDER_NOT_CANCELLABLE", self.request()["code"])
        self.record("shipped orders cannot be cancelled", "PASS")

    def test_stale_order_and_expired_request(self):
        request = self.request()
        sql(f"UPDATE order_demo.orders SET status='PENDING' WHERE id={self.order}")
        self.assertEqual("ORDER_CHANGED", self.confirm(request["requestId"])["code"])
        request = self.request()
        sql(f"UPDATE order_demo.cancellation_requests SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id='{request['requestId']}'")
        self.assertEqual("REQUEST_EXPIRED", self.confirm(request["requestId"])["code"])
        self.assertEqual("PENDING", self.status())
        self.record("stale version and expiry both reject confirmation", "PASS")

    def test_concurrent_confirmation_commits_once(self):
        request = self.request()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: self.confirm(request["requestId"]), range(2)))
        self.assertEqual(results[0], results[1])
        self.assertEqual("CANCELLED", self.status())
        count = sql(f"SELECT COUNT(*) FROM order_demo.action_audit WHERE request_id='{request['requestId']}' AND event='CANCELLED'").stdout.strip()
        self.assertEqual("1", count)
        self.record("concurrent confirmations create one cancellation event", "PASS")

    def test_shipping_race_has_one_terminal_outcome(self):
        request = self.request()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            cancel = pool.submit(self.confirm, request["requestId"])
            ship = pool.submit(sql, f"UPDATE order_demo.orders SET status='SHIPPED' WHERE id={self.order} AND status IN ('PENDING','PROCESSING')")
            result = cancel.result()
            ship.result()
        self.assertIn(self.status(), ("CANCELLED", "SHIPPED"))
        self.assertEqual(self.status() == "CANCELLED", result["ok"])
        self.record("conditional shipping races with cancellation", {"finalStatus": self.status(), "confirmation": result})


if __name__ == "__main__":
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(BoundaryTests))
    output = ROOT / "target/mrs/boundary-results.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"testsRun": result.testsRun, "failures": len(result.failures),
        "errors": len(result.errors), "checks": EVIDENCE}, indent=2) + "\n")
    raise SystemExit(0 if result.wasSuccessful() else 1)
