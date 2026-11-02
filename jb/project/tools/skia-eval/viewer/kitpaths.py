"""Where the kit is, where the repository is, where everything generated goes (build/skia-eval-out, git-ignored;
SKIA_EVAL_OUT overrides). Imported by the viewer tooling; the shell scripts source env.sh for the same."""
import os
KIT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.abspath(os.path.join(KIT, '..', '..', '..', '..'))
OUT_ROOT = os.environ.get('SKIA_EVAL_OUT') or os.path.join(REPO, 'build', 'skia-eval-out')
