import asyncio
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, call, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import app.main as main


def queue_payload(task_id: int) -> str:
    return json.dumps(
        {
            "task_id": task_id,
            "submission_id": task_id + 100,
            "code_json": {"total_files": 1, "file_tree": []},
            "rubric_json": {"dimensions": [{"name": "功能", "max_score": 100}]},
        }
    )


class WorkerConfigurationTests(unittest.TestCase):
    def test_bounded_int_defaults_and_clamps(self) -> None:
        self.assertEqual(5, main.bounded_int(None, 5, 1, 10))
        self.assertEqual(5, main.bounded_int("invalid", 5, 1, 10))
        self.assertEqual(1, main.bounded_int("0", 5, 1, 10))
        self.assertEqual(10, main.bounded_int("11", 5, 1, 10))
        self.assertEqual(8, main.bounded_int("8", 5, 1, 10))


class WorkerConcurrencyTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        main.ACTIVE_WORKER_IDS.clear()

    async def asyncTearDown(self) -> None:
        main.ACTIVE_WORKER_IDS.clear()

    async def test_lifespan_starts_configured_number_of_workers(self) -> None:
        async def idle_worker(_: int) -> None:
            await asyncio.Event().wait()

        with (
            patch.object(main, "WORKER_ENABLED", True),
            patch.object(main, "REDIS_URL", "redis://example.invalid/0"),
            patch.object(main, "WORKER_CONCURRENCY", 3),
            patch.object(main, "redis_worker", side_effect=idle_worker) as worker_mock,
        ):
            async with main.lifespan(main.app):
                await asyncio.sleep(0)
                self.assertEqual([call(1), call(2), call(3)], worker_mock.call_args_list)
                health = await main.health()
                self.assertEqual(3, health["worker_concurrency"])
                self.assertEqual(3, health["workers_alive"])

        self.assertEqual([], main.RUNNING_WORKER_TASKS)

    async def test_two_queue_items_can_score_at_the_same_time(self) -> None:
        client = AsyncMock()
        client.sismember.return_value = False
        all_started = asyncio.Event()
        release = asyncio.Event()
        started_task_ids: list[int | None] = []

        async def blocking_score(request: main.ScoreRequest) -> dict[str, object]:
            started_task_ids.append(request.task_id)
            if len(started_task_ids) == 2:
                all_started.set()
            await release.wait()
            return {"total_score": 100, "dimensions": []}

        with (
            patch.object(main, "score", side_effect=blocking_score),
            patch.object(main, "post_callback_with_retry", new=AsyncMock()) as callback_mock,
        ):
            tasks = [
                asyncio.create_task(main.process_queue_item(client, queue_payload(1), 1)),
                asyncio.create_task(main.process_queue_item(client, queue_payload(2), 2)),
            ]
            await asyncio.wait_for(all_started.wait(), timeout=1)
            self.assertEqual({1, 2}, main.ACTIVE_WORKER_IDS)
            release.set()
            await asyncio.gather(*tasks)

        self.assertEqual(set(), main.ACTIVE_WORKER_IDS)
        self.assertEqual(2, callback_mock.await_count)
        self.assertTrue(all(item.args[0]["status"] == "success" for item in callback_mock.await_args_list))

    async def test_success_callback_retries_without_changing_status(self) -> None:
        payload = {"taskId": 1, "status": "success", "result": {"total_score": 100}}
        callback = AsyncMock(side_effect=[RuntimeError("temporary"), None])

        with (
            patch.object(main, "post_callback", new=callback),
            patch.object(main.asyncio, "sleep", new=AsyncMock()),
        ):
            await main.post_callback_with_retry(payload)

        self.assertEqual(2, callback.await_count)
        self.assertTrue(all(item.args[0]["status"] == "success" for item in callback.await_args_list))

    async def test_cancelled_while_scoring_discards_result(self) -> None:
        client = AsyncMock()
        client.sismember.side_effect = [False, True]

        with (
            patch.object(main, "score", new=AsyncMock(return_value={"total_score": 100, "dimensions": []})),
            patch.object(main, "post_callback_with_retry", new=AsyncMock()) as callback_mock,
        ):
            await main.process_queue_item(client, queue_payload(9), 1)

        callback_mock.assert_not_awaited()
        client.srem.assert_awaited_once_with(main.REDIS_CANCELLED_TASKS, "9")
        self.assertEqual(set(), main.ACTIVE_WORKER_IDS)


if __name__ == "__main__":
    unittest.main()
