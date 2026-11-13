"""BE AI attestation from the AI source alone (no network, nothing from the AI tree is imported or run).

The attestation is what BE AiPipelineConfiguration reads: {"releaseManifest": <AI release manifest, verbatim>,
"expectedMeta": {<profile>: <ModelStamp> | [<ModelStamp>, ...]}}. BE quarantines AI on the first response whose
7-field meta equals none of the expected stamps, so the stamps are computed exactly as the AI server builds them:

  reasoning profiles   app/services/reasoning/__init__.py REASONING_PROFILES; modelRevision = sha256 over
                       app/services/<files> in order, first 12 hex (code_revision)
  LLM profiles         provider = release provider.name, model = release provider.models.<role>,
                       modelRevision "<model>@<code hash>"; derive also answers without the LLM (local/llm/<hash>)
  extract profiles     cfg/idf versions of the pinned IDF; unchanged while the release IDF is unchanged, so the
                       current attestation's stamp is kept (the AI server refuses to start on an IDF mismatch)
  embedding profiles   app/core/config.py EMBEDDING_PROFILES and the release model pin

Ported from the reviewed cold-start tool (prep/be-v5/make-attestation.py, offline part); the current production
attestation must reproduce byte for byte before anything new is trusted (ai_auto.py checks it every run).
"""
import ast
import hashlib
import json

from contract import ReleaseError, require
from host import AI_EMBEDDING, STAMP_LIMITS

STAMP_FIELDS = tuple(STAMP_LIMITS)
# Reasoning operation -> release provider.models role.
LLM_ROLE = {'explain': 'explain', 'facts': 'facts', 'derive': 'sound'}


def content(value):
    """Byte format of the host AI content files: sorted keys, 2-space indent, UTF-8, final newline."""
    return (json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + '\n').encode()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def _literal(node, names):
    if isinstance(node, ast.Constant):
        return node.value
    if isinstance(node, ast.Name):
        require(node.id in names, 'Unknown name in AI profile table')
        return names[node.id]
    if isinstance(node, ast.Tuple):
        out = []
        for item in node.elts:
            if isinstance(item, ast.Starred):
                out.extend(_literal(item.value, names))
            else:
                out.append(_literal(item, names))
        return tuple(out)
    raise ReleaseError('Unsupported expression in AI profile table')


def table(text, variable, constructor):
    """{id: fields} from `<variable> = {p.id: p for p in (<constructor>(...), ...)}`."""
    tree = ast.parse(text)
    names, fields, calls = {}, None, None
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            name = node.targets[0].id
            if isinstance(node.value, ast.Tuple) and name.startswith('_'):
                names[name] = _literal(node.value, names)
            if name == variable:
                require(isinstance(node.value, ast.DictComp), 'Unexpected AI profile table shape')
                calls = node.value.generators[0].iter.elts
        if isinstance(node, ast.ClassDef) and node.name == constructor:
            fields = [n.target.id for n in node.body if isinstance(n, ast.AnnAssign)]
    require(calls is not None and fields, 'AI profile table not found: ' + variable)
    out = {}
    for call in calls:
        require(isinstance(call, ast.Call) and getattr(call.func, 'id', None) == constructor, 'Unexpected AI profile entry')
        value = dict(zip(fields, (_literal(a, names) for a in call.args)))
        value.update({k.arg: _literal(k.value, names) for k in call.keywords})
        out[value['id']] = value
    return out


class Source:
    """AI repository files at one commit: `read(path) -> bytes`, and `tree(path) -> git tree id` of a directory."""

    def __init__(self, read, tree=None):
        self.read = read
        self.tree = tree

    def profiles(self):
        config = self.read('app/core/config.py').decode()
        return (table(self.read('app/services/reasoning/__init__.py').decode(), 'REASONING_PROFILES', 'ReasoningProfile'),
                table(config, 'EXTRACT_PROFILES', 'ExtractProfile'), table(config, 'EMBEDDING_PROFILES', 'EmbeddingProfile'))

    def code_revision(self, files):
        digest = hashlib.sha256()
        for name in files:
            digest.update(self.read('app/services/' + name))
        return digest.hexdigest()[:12]


def stamp(profile, provider, model, revision, prompt, pre='md-plain-1'):
    return {'profileVersion': profile, 'provider': provider, 'model': model, 'modelRevision': revision[:120],
            'promptVersion': prompt, 'schemaVersion': '1.0', 'preprocessingVersion': pre}


def stamps(entry):
    if isinstance(entry, dict):
        return [entry]
    require(isinstance(entry, list) and 1 <= len(entry) <= 8, 'Invalid expected stamp entry')
    return list(entry)


def expected(source, release, used, current):
    """profile -> [stamps] for the profiles BE uses (`used`), computed from `source` for `release`."""
    reasoning, extract, embedding = source.profiles()
    llm = release['provider']
    out = {}
    for profile in sorted(set(used)):
        if profile in reasoning:
            p = reasoning[profile]
            revision = source.code_revision(p['files'])
            if p['model'] != 'llm':
                out[profile] = [stamp(profile, 'local', p['model'], revision, p['prompt'])]
                continue
            require(p['operation'] in LLM_ROLE, 'LLM profile with an unknown provider role: ' + profile)
            model = llm['models'][LLM_ROLE[p['operation']]]
            with_llm = stamp(profile, llm['name'], model, '%s@%s' % (model, revision), p['prompt'])
            if p['operation'] == 'derive':
                out[profile] = [dict(with_llm, provider='local+' + llm['name']), stamp(profile, 'local', 'llm', revision, p['prompt'])]
            else:
                out[profile] = [with_llm]
        elif profile in extract:
            require('llm' not in extract[profile].get('relations', '').split('+') and not extract[profile].get('concept_filter'),
                    'Extract profile is not deterministic: ' + profile)
            require(profile in current, 'No current stamp for the extract profile ' + profile)
            out[profile] = stamps(current[profile])
        elif profile in embedding:
            p = embedding[profile]
            require(p['backend'] == 'sentence-transformers' and (p['model'], p['revision']) == (AI_EMBEDDING['name'], AI_EMBEDDING['revision'])
                    and (release['model']['name'], release['model']['revision']) == (p['model'], p['revision']),
                    'Embedding profile differs from the release model')
            out[profile] = [stamp(profile, 'local', p['model'], p['revision'], None,
                                  'prefix:%s-normalized-v1' % (p.get('prefix', '').strip() or 'none'))]
        else:
            raise ReleaseError('Profile not in the AI source: ' + profile)
    return out


def listed(source):
    """Every profile id the AI source serves."""
    reasoning, extract, embedding = source.profiles()
    return set(reasoning) | set(extract) | set(embedding)


def build(release, meta):
    """Attestation document; a single stamp is written as an object, several as a list (BE accepts both)."""
    for entry in meta.values():
        require(1 <= len(entry) <= 8, 'A profile needs 1..8 expected stamps')
    return {'releaseManifest': release, 'expectedMeta': {p: (s if len(s) > 1 else s[0]) for p, s in meta.items()}}


def union(old, new):
    """Stamps accepted while the AI switches: every current stamp plus every new one, current first."""
    out = {}
    for profile in sorted(set(old) | set(new)):
        merged = []
        for value in stamps(old[profile]) if profile in old else []:
            if value not in merged:
                merged.append(value)
        for value in new.get(profile, []):
            if value not in merged:
                merged.append(value)
        out[profile] = merged
    return out


def normalized(meta):
    return {p: stamps(v) for p, v in meta.items()}


def schema_problems(be, ai, path=''):
    """BE pinned schemas.json must accept every AI response: equal except BE `required` may be a subset."""
    if isinstance(be, dict) and isinstance(ai, dict):
        problems = []
        for key in sorted(set(be) | set(ai)):
            where = path + '/' + key
            if key not in be or key not in ai:
                # A BE-only model (none expected) or a field AI dropped/added that BE does not know.
                if not (key == 'required' and key in ai and key not in be):
                    problems.append(where)
            elif key == 'required' and isinstance(be[key], list) and isinstance(ai[key], list):
                if not set(be[key]) <= set(ai[key]):
                    problems.append(where)
            else:
                problems += schema_problems(be[key], ai[key], where)
        return problems
    return [] if be == ai else [path or '/']
