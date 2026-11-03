"""Where the kit is, where the repository is, where everything generated goes (build/skia-eval-out, git-ignored;
SKIA_EVAL_OUT overrides). Imported by the viewer tooling; the shell scripts source env.sh for the same."""
import os
KIT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.abspath(os.path.join(KIT, '..', '..', '..', '..'))
OUT_ROOT = os.environ.get('SKIA_EVAL_OUT') or os.path.join(REPO, 'build', 'skia-eval-out')


# A generated sheet arm (text-reference/arms.py) spells its settings in its name, `key-value` joined by `+`:
# the viewer's dimensions, title and description come from the name, not from an entry per arm.
ARM_NAME_KEYS = ('pipe', 'scaler', 'strike', 'layout', 'shaping', 'paragraphs', 'darkening', 'blend', 'decorations')
_PIPE_TITLES = {'software': 'software loops', 'metal': 'Metal pipe', 'skia': 'Skia backend', 'd3d': 'Direct3D pipe', 'opengl': 'OpenGL pipe'}

def arm_from_name(label):
    """{'dims': …, 'title': …, 'description': …} for a generated arm name, None for any other label."""
    parts = label.split('+')
    kv = {}
    for part in parts:
        k, _, v = part.partition('-')
        if k not in ARM_NAME_KEYS or not v: return None
        kv[k] = v
    if 'pipe' not in kv or 'scaler' not in kv: return None
    dims = {'renderer': kv['pipe'], 'scaler': kv['scaler'], 'strike': kv.get('strike', 'java2d'), 'blend': kv.get('blend', 'plain'),
            'decorations': kv.get('decorations', 'system')}
    if 'layout' in kv:
        dims['layout'] = 'java2d' if kv['layout'] == 'java2d' else 'skia'
    else:   # names of the first matrix (2026-11-03 morning): shaping and paragraphs apart
        shaping, paragraphs = kv.get('shaping', 'java2d'), kv.get('paragraphs', 'java2d')
        dims['layout'] = {('java2d', 'java2d'): 'java2d', ('skia', 'java2d'): 'skia shaper', ('skia', 'skia'): 'skia paragraph',
                          ('java2d', 'skia'): 'skia paragraph only'}[(shaping, paragraphs)]
    if 'darkening' in kv: dims['darkening'] = kv['darkening']
    words = [_PIPE_TITLES.get(kv['pipe'], kv['pipe']), 'platform scaler' if kv['scaler'] == 'platform' else 'Skia scaler']
    if dims['strike'] == 'skia': words.append("Skia's text path")
    if dims['layout'] != 'java2d': words.append("Skia's layout" if dims['layout'] == 'skia' else dims['layout'])
    if 'darkening' in kv: words.append(f"darkening {kv['darkening']}")
    words.append(f"blend {dims['blend']}")
    words.append(f"decorations {dims['decorations']}")
    title = ', '.join(words)
    description = ('A generated arm of the text settings matrix: ' + '; '.join(f'{k} {v}' for k, v in kv.items())
                   + '. The dimension notes describe what each value does.')
    return {'dims': dims, 'title': title, 'description': description}
