from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "grib-native-bundle.yml"


class GribNativeBundleWorkflowPolicyTest(unittest.TestCase):
    def test_publication_uses_isolated_temporary_repository(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        publish_repo = workflow.index(
            'publish_repo="$RUNNER_TEMP/meteoone-generated-publish-repo"'
        )
        init = workflow.index('git -C "$publish_repo" init -q', publish_repo)
        fetch = workflow.index('git -C "$publish_repo" fetch', init)
        switch = workflow.index('git -C "$publish_repo" switch', fetch)
        remove = workflow.index(
            'git -C "$publish_repo" rm -rf --ignore-unmatch .',
            switch,
        )
        copy = workflow.index(
            'cp -a "$PUBLISH_INPUT/bundle/." "$publish_repo/bundle/"',
            remove,
        )
        push = workflow.index(
            'git -C "$publish_repo" push origin',
            copy,
        )

        self.assertLess(publish_repo, init)
        self.assertLess(init, fetch)
        self.assertLess(fetch, switch)
        self.assertLess(switch, remove)
        self.assertLess(remove, copy)
        self.assertLess(copy, push)
        self.assertNotIn('git reset --hard "${GITHUB_SHA}"', workflow)
        self.assertNotIn('git switch -c publish-grib-native', workflow)


if __name__ == "__main__":
    unittest.main()
