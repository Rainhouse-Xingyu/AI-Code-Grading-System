import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.main import ScoreRequest, build_prompt, prepare_code_json_for_prompt


class PromptTruncationTests(unittest.TestCase):
    def test_small_project_keeps_full_content(self) -> None:
        code_json = {
            "total_files": 1,
            "file_tree": [{"path": "src/main.py", "content": "print('ok')"}],
        }

        prompt_json = prepare_code_json_for_prompt(code_json)

        self.assertEqual("full", prompt_json["prompt_truncation"]["mode"])
        self.assertEqual("print('ok')", prompt_json["file_tree"][0]["content"])

    def test_medium_project_prioritizes_core_files(self) -> None:
        helper_content = "\n".join(f"line {idx}" for idx in range(300))
        code_json = {
            "total_files": 2,
            "file_tree": [
                {"path": "docs/notes.txt", "content": helper_content * 8},
                {"path": "src/main.py", "content": "def main():\n    return 'important'"},
            ],
        }

        prompt_json = prepare_code_json_for_prompt(code_json)
        files = {item["path"]: item for item in prompt_json["file_tree"]}

        self.assertEqual("core_files", prompt_json["prompt_truncation"]["mode"])
        self.assertEqual("full", files["src/main.py"]["prompt_selected"])
        self.assertEqual("preview", files["docs/notes.txt"]["prompt_selected"])
        self.assertTrue(files["docs/notes.txt"]["content_truncated"])

    def test_large_project_keeps_first_100_lines_per_file(self) -> None:
        content = "\n".join(f"line {idx}" for idx in range(160))
        code_json = {
            "total_files": 2,
            "file_tree": [
                {"path": "src/main.py", "content": content * 120},
                {"path": "src/service.py", "content": content * 120},
            ],
        }

        prompt_json = prepare_code_json_for_prompt(code_json)

        self.assertEqual("summary", prompt_json["prompt_truncation"]["mode"])
        for file_item in prompt_json["file_tree"]:
            self.assertEqual(100, len(file_item["content"].splitlines()))
            self.assertTrue(file_item["content_truncated"])

    def test_build_prompt_uses_truncated_code_json(self) -> None:
        content = "\n".join(f"line {idx}" for idx in range(160))
        request = ScoreRequest(
            code_json={
                "total_files": 1,
                "file_tree": [{"path": "src/main.py", "content": content * 120}],
            },
            rubric_json={"dimensions": [{"name": "功能", "max_score": 100}]},
        )

        prompt = build_prompt(request)

        self.assertIn('"mode": "summary"', prompt)
        self.assertNotIn("line 159line 0", prompt)


if __name__ == "__main__":
    unittest.main()
