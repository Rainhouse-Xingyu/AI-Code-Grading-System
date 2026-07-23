import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import app.main as main


class ModelSourceTests(unittest.IsolatedAsyncioTestCase):
    def request(self) -> main.ScoreRequest:
        return main.ScoreRequest(
            code_json={"total_files": 1, "file_tree": []},
            rubric_json={"dimensions": [{"name": "功能", "max_score": 100}]},
        )

    async def test_local_deepseek_model_is_marked_as_local_source(self) -> None:
        response = {"model_name": "deepseek-r1:14b", "token_usage": 321}
        with patch.object(
            main,
            "score_with_openai_compatible",
            new=AsyncMock(return_value=response),
        ):
            result = await main.score_with_local_model(self.request())

        self.assertEqual("deepseek-r1:14b", result["model_name"])
        self.assertEqual("local", result["model_source"])

    async def test_remote_deepseek_model_is_marked_as_deepseek_source(self) -> None:
        response = {"model_name": "DeepSeek/DeepSeek-R1", "token_usage": 654}
        with patch.object(
            main,
            "score_with_openai_compatible",
            new=AsyncMock(return_value=response),
        ):
            result = await main.score_with_deepseek(self.request())

        self.assertEqual("deepseek", result["model_source"])


if __name__ == "__main__":
    unittest.main()
