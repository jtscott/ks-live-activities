#!/usr/bin/env python3
"""Check kiosk-satellite-plugin.json against Kiosk Satellite's own manifest rules.

A port of the settings rules in KS's PluginManifest.kt (2026.10.38), which rejects a manifest on install. Older KS
versions show number fields with "input": "field" as sliders, so every number range here must also suit a slider.
It also checks that each visibleWhen value is one the select it names can hold, which KS does not check.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TYPES = {'string', 'secret', 'boolean', 'number', 'color', 'select', 'entity', 'icon', 'dashboard', 'cameraView', 'list'}
TEXT_TYPES = {'string', 'entity', 'icon', 'dashboard', 'cameraView'}
CAPABILITIES = {'overlay', 'native', 'entities', 'host.read', 'host.control', 'ha', 'shizuku', 'screensaver',
                'screensaver.mode', 'noise', 'voice.skin', 'voice.text', 'camera', 'barcode', 'ma'}


def fail(message):
    raise SystemExit('Manifest: ' + message)


def text(obj, key, limit):
    value = obj.get(key)
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        fail(f'invalid {key} in {obj.get("key", obj.get("id"))}')
    return value


def default_of(field):
    if 'default' in field:
        return field['default']
    return {'boolean': False, 'number': field.get('min'), 'color': '#FFFFFF', 'list': []}.get(
        field['type'], field['options'][0] if field['type'] == 'select' else '')


def check_value(field, value):
    kind = field['type']
    name = field['key']
    if kind in ('string', 'secret'):
        ok = isinstance(value, str) and len(value) <= 512
    elif kind == 'entity':
        ok = isinstance(value, str) and (value == '' or (len(value) <= 255 and re.fullmatch(r'[a-z0-9_]+\.[a-z0-9_]+', value)))
    elif kind == 'icon':
        ok = isinstance(value, str) and (value == '' or (len(value) <= 80 and re.fullmatch(r'mdi:[a-z0-9]+(?:-[a-z0-9]+)*', value)))
    elif kind == 'boolean':
        ok = isinstance(value, bool)
    elif kind == 'color':
        ok = isinstance(value, str) and re.fullmatch(r'#[a-fA-F0-9]{6}', value)
    elif kind == 'number':
        lo, hi, step = field['min'], field['max'], field.get('step', 1)
        steps = (hi - lo) / step
        # Every KS version before typed fields shows a slider, so the range must suit one.
        ok = (isinstance(value, (int, float)) and not isinstance(value, bool) and lo < hi and step > 0
              and 1 <= steps <= 10000 and abs(steps - round(steps)) < 1e-5 and lo <= value <= hi
              and abs((value - lo) / step - round((value - lo) / step)) < 1e-5)
        if field.get('input', 'slider') not in ('slider', 'field'):
            ok = False
    elif kind == 'select':
        options = field.get('options', [])
        ok = (1 <= len(options) <= 32 and len(set(options)) == len(options)
              and all(isinstance(o, str) and o.strip() and len(o) <= 80 for o in options) and value in options)
    elif kind == 'list':
        ok = isinstance(value, list) and len(value) <= field.get('maxItems', 32)
    else:
        ok = False
    if not ok:
        fail(f'{name}: default {value!r} is not valid for its type')


def check_condition(condition, earlier):
    if not isinstance(condition, dict):
        fail('invalid condition')
    target = earlier.get(condition.get('key'))
    if target is None or target['type'] in ('list', 'secret'):
        fail(f'condition names {condition.get("key")!r}, which is not an earlier field')
    tests = [k for k in condition if k != 'key']
    if len(tests) != 1 or tests[0] not in ('equals', 'notEquals', 'notEmpty'):
        fail(f'condition on {condition["key"]} needs one test')
    if tests[0] == 'notEmpty':
        if condition['notEmpty'] is not True:
            fail('notEmpty must be true')
        return
    values = condition[tests[0]] if isinstance(condition[tests[0]], list) else [condition[tests[0]]]
    if not 1 <= len(values) <= 32 or not all(isinstance(v, (str, bool, int, float)) for v in values):
        fail(f'condition on {condition["key"]} has invalid values')
    if target['type'] == 'select' and not all(v in target['options'] for v in values):
        fail(f'condition on {condition["key"]} names a value its select does not offer: {values}')


def check_fields(fields, depth):
    earlier = {}
    for field in fields:
        key = text(field, 'key', 64)
        if not re.fullmatch(r'[a-zA-Z][a-zA-Z0-9_]*', key) or key in earlier:
            fail(f'invalid or duplicate key {key}')
        text(field, 'title', 80)
        if 'description' in field:
            text(field, 'description', 400)
        kind = field.get('type')
        if kind not in TYPES:
            fail(f'{key}: unknown type {kind}')
        if depth > 0 and (kind == 'secret' or 'group' in field):
            fail(f'{key}: row fields take no secret or group')
        if kind == 'list' and depth >= 2:
            fail(f'{key}: lists nest one level')
        if 'required' in field and not (depth > 0 and isinstance(field['required'], bool) and kind in TEXT_TYPES):
            fail(f'{key}: required is for text row fields')
        if 'visibleWhen' in field:
            check_condition(field['visibleWhen'], earlier)
        if 'input' in field and kind != 'number':
            fail(f'{key}: input is for numbers')
        if kind == 'list':
            check_list(field, depth)
        if depth == 0 and 'default' not in field:
            fail(f'{key}: settings need a default')
        check_value(field, default_of(field))
        earlier[key] = field


def check_list(field, depth):
    fields = field.get('fields')
    if not isinstance(fields, list) or not 1 <= len(fields) <= 16:
        fail(f'{field["key"]}: a list has 1 to 16 fields')
    check_fields(fields, depth + 1)
    by_key = {f['key']: f for f in fields}
    if not 1 <= field.get('maxItems', 32) <= 32:
        fail(f'{field["key"]}: maxItems')
    if 'addTitle' in field:
        text(field, 'addTitle', 40)
    row = field.get('row')
    if row is None:
        return
    if not set(row) <= {'title', 'subtitle', 'icon'}:
        fail(f'{field["key"]}: row keys')

    def shown(key):
        return key in by_key and by_key[key]['type'] != 'list'
    if 'title' in row and not shown(row['title']):
        fail(f'{field["key"]}: row title')
    if 'icon' in row and by_key.get(row['icon'], {}).get('type') != 'icon':
        fail(f'{field["key"]}: row icon')
    if 'subtitle' in row:
        keys = row['subtitle'] if isinstance(row['subtitle'], list) else [row['subtitle']]
        if not 1 <= len(keys) <= 4 or not all(shown(k) for k in keys):
            fail(f'{field["key"]}: row subtitle')


def main():
    raw = (ROOT / 'kiosk-satellite-plugin.json').read_bytes()
    if len(raw) > 32 * 1024:
        fail('larger than 32 KB')
    manifest = json.loads(raw)
    if manifest.get('schemaVersion') != 1 or manifest.get('apiVersion') != 1:
        fail('schemaVersion and apiVersion must be 1')
    if not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', manifest['id']) or len(manifest['id']) > 64:
        fail('id')
    text(manifest, 'name', 80)
    text(manifest, 'description', 1000)
    text(manifest, 'author', 120)
    if not set(manifest['capabilities']) <= CAPABILITIES:
        fail('unknown capability')
    settings = manifest['settings']
    if len(settings) > 50:
        fail('more than 50 settings')
    check_fields(settings, 0)
    top = {s['key']: s for s in settings}
    ids = set()
    for button in manifest.get('buttons', []):
        bid = text(button, 'id', 64)
        if not re.fullmatch(r'[a-z][a-zA-Z0-9]*', bid) or bid in ids:
            fail(f'button {bid}')
        ids.add(bid)
        text(button, 'title', 80)
        text(button, 'label', 40)
        if 'description' in button:
            text(button, 'description', 400)
        if 'visibleWhen' in button:
            check_condition(button['visibleWhen'], top)
    commands = manifest.get('commands', [])
    if len(commands) > 20 or len({c['id'] for c in commands}) != len(commands):
        fail('commands')
    for command in commands:
        if not re.fullmatch(r'[a-z][a-zA-Z0-9]*', command['id']):
            fail(f'command {command["id"]}')
        text(command, 'title', 80)
    groups = {s.get('group') for s in settings} | {b.get('group', 'Settings') for b in manifest.get('buttons', [])}
    for group in manifest.get('groups', []):
        if group['title'] not in groups:
            fail(f'group {group["title"]} has no settings')
    print('Manifest follows the Kiosk Satellite rules.')


if __name__ == '__main__':
    main()
