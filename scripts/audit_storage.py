"""Read-only size audit of an inkdata COPY (never proposes a deletion list).

Usage: python scripts/audit_storage.py path/to/inkdata > audit.json
Only ordinary MISQ0001/2 note bodies are analyzed; block descriptors are counted
but not assembled. Compression sizes are experiments, not a new file format.
"""
import base64
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import zlib


def sha(data):
    return hashlib.sha256(data).hexdigest()


def manifest_refs(encoded):
    source = io.BytesIO(base64.b64decode(encoded))

    def read(fmt):
        return struct.unpack('>' + fmt, source.read(struct.calcsize('>' + fmt)))[0]

    def utf():
        return source.read(read('H'))

    assert read('I') == 0x484E4D31
    version = read('I')
    assert 1 <= version <= 4
    utf()  # UUID
    utf()  # Java modified UTF-8 title: no decoding needed.
    source.read(36)  # dates, viewport, brush color/size
    if version >= 2:
        source.read(16 + (4 if version >= 3 else 0) + (12 if version >= 4 else 0))
    read('q')
    count = read('I')
    assert count <= 1_000_000
    result = []
    for _ in range(count):
        read('q')
        result.append(utf().decode('ascii'))
    assert not source.read(1)
    return result


def union_size(ranges):
    end = total = 0
    for start, stop in sorted(ranges):
        assert 0 <= start <= stop
        total += max(0, stop - max(start, end))
        end = max(end, stop)
    return total


def note_audit(path):
    with path.open('rb') as source:
        magic = source.read(8)
        if magic == b'MISQBLK1':
            return {'path': str(path), 'bytes': path.stat().st_size, 'block_descriptor': True}
        assert magic in (b'MISQ0001', b'MISQ0002'), 'Unsupported note'
        slot_size = 192 if magic == b'MISQ0002' else 64
        header_size = 8 + slot_size * 2

        def payload(ref):
            assert ref['offset'] >= header_size and 0 <= ref['size'] <= 128 * 1024 * 1024
            source.seek(ref['offset'])
            data = source.read(ref['size'])
            assert len(data) == ref['size'] and sha(data) == ref['sha256']
            return data

        indexes, ranges, errors = [], [(0, header_size)], []
        for slot in range(2):
            source.seek(8 + slot * slot_size)
            raw = source.read(slot_size)
            if not any(raw):
                continue
            try:
                if slot_size == 192:
                    assert sha(raw[:-32]) == raw[-32:].hex()
                generation = struct.unpack_from('>q', raw)[0]
                assert generation > 0
                refs = []
                for position in range(1 if slot_size == 64 else 3):
                    offset, size = struct.unpack_from('>qI', raw, 8 + position * 44)
                    digest = raw[20 + position * 44:52 + position * 44].hex()
                    refs.append({'offset': offset, 'size': size, 'sha256': digest} if offset else None)
                index_body = payload(refs[0])
                index = json.loads(index_body)
                assert index['generation'] == generation
                kept = [ref for ref in refs if ref] + list(index['records'].values())
                if index.get('thumbnail'):
                    kept.append(index['thumbnail'])
                slot_ranges = []
                for ref in kept:
                    payload(ref)
                    slot_ranges.append((ref['offset'], ref['offset'] + ref['size']))
                indexes.append((generation, index, index_body))
                ranges.extend(slot_ranges)
            except (AssertionError, ValueError, KeyError, struct.error) as error:
                errors.append({'slot': slot, 'error': type(error).__name__ + ': ' + str(error)})
        assert indexes, 'No verified index'
        _, index, body = max(indexes, key=lambda value: value[0])
        current = set(manifest_refs(index['manifest']))
        history = {event['blob'] for event in index['events'] if event['blob']}
        records = index['records']
        assert current <= records.keys() and history <= records.keys()
        sizes = {key: ref['size'] for key, ref in records.items()}
        image_hashes, ink_sizes, brush_headers = {}, [], set()
        image_total = ink_total = ink_zlib = brush_total = input_total = 0
        for key, ref in records.items():
            data = payload(ref)
            if data[:4] == b'HNI1':
                assert len(data) >= 28 and struct.unpack_from('>I', data, 24)[0] == len(data) - 28
                png = data[28:]
                image_total += len(png)
                image_hashes[sha(png)] = len(png)
            elif data[:4] == b'HNS1':
                # Measure the exact repeated brush settings, without decoding
                # or changing any Ink input samples.
                kind = struct.unpack_from('>I', data, 8)[0]
                header = 28 if kind in (1, 2, 3) else 36 if kind == 4 else None
                if kind in (5, 6, 7, 8):
                    curve_type = struct.unpack_from('>I', data, 12)[0]
                    assert curve_type in range(5)
                    if curve_type in (0, 1):
                        curve_size = 12 if curve_type == 0 else 24
                    else:
                        count = struct.unpack_from('>I', data, 20)[0]
                        assert 2 <= count <= 101
                        curve_size = 12 + count * (20 if curve_type == 4 else 8)
                    header = 28 + curve_size
                if header:
                    assert struct.unpack_from('>I', data, header - 4)[0] == len(data) - header
                    brush_headers.add(data[:header - 4])
                    brush_total += header - 4
                    input_total += len(data) - header
                ink_sizes.append(len(data))
                ink_total += len(data)
                ink_zlib += len(zlib.compress(data))
        size = path.stat().st_size
        return {
            'path': str(path), 'bytes': size, 'verified_slots': len(indexes), 'slot_errors': errors,
            'current_records': len(current), 'records': len(records), 'events': len(index['events']),
            'current_payload_bytes': sum(sizes[key] for key in current),
            'history_only_payload_bytes': sum(sizes[key] for key in history - current),
            'unreferenced_payload_bytes': sum(sizes[key] for key in records.keys() - current - history),
            'outside_both_slots_bytes': size - union_size(ranges),
            'latest_index_bytes': len(body), 'latest_index_zlib_bytes': len(zlib.compress(body)),
            'index_fields_bytes': {key: len(json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()) for key, value in index.items()},
            'ink_records': len(ink_sizes), 'ink_bytes': ink_total, 'ink_individual_zlib_bytes': ink_zlib,
            'brush_variants': len(brush_headers),
            'brush_header_bytes': brush_total, 'unique_brush_header_bytes': sum(map(len, brush_headers)),
            'ink_input_bytes': input_total,
            'embedded_png_bytes': image_total, 'unique_png_bytes': sum(image_hashes.values()),
        }


def audit(root, recovery_inventory=None):
    result = {'root': str(root.resolve()), 'warning': 'Snapshot audit only; unreferenced does not mean safe to delete.'}
    recovery = root / '.recovery'
    revisions = sorted(recovery.glob('revision-*.json'), reverse=True)
    roots = set()
    revision_errors = []
    for path in revisions:
        try:
            envelope = json.loads(path.read_bytes())
            assert sha(envelope['body'].encode()) == envelope['sha256']
            roots.update('blob-' + value for value in json.loads(envelope['body'])['files'].values())
        except (AssertionError, ValueError, KeyError) as error:
            revision_errors.append({'path': str(path), 'error': type(error).__name__})
    blobs = {path.name: path.stat().st_size for path in recovery.glob('blob-*')}
    if recovery_inventory:
        # Optional `adb shell ls -ln` capture: use names/sizes without copying
        # the historical payloads. This is inventory evidence, not validation
        # of the actual blob contents.
        import re
        blobs = {}
        for line in recovery_inventory.read_text(encoding='utf-8-sig').splitlines():
            parts = line.split()
            if len(parts) >= 8 and parts[0].startswith('-') and re.fullmatch(r'blob-[a-f0-9]{64}', parts[-1]):
                blobs[parts[-1]] = int(parts[4])
    result['recovery'] = {
        'revision_count': len(revisions), 'revision_errors': revision_errors,
        'blob_inventory_source': str(recovery_inventory) if recovery_inventory else 'local copy',
        'blob_payloads_verified': False,
        'blob_count': len(blobs), 'blob_bytes': sum(blobs.values()),
        'not_referenced_by_retained_revisions_count': len(blobs.keys() - roots),
        'not_referenced_by_retained_revisions_bytes': sum(size for name, size in blobs.items() if name not in roots),
        'missing_referenced_blobs': sorted(roots - blobs.keys()),
    }
    result['directory_sizes_scope'] = 'local copy only'
    result['directories'] = {}
    for name in ('.recovery', '.blocks', '.regions'):
        paths = [path for path in (root / name).rglob('*') if path.is_file()]
        result['directories'][name] = {'files': len(paths), 'bytes': sum(path.stat().st_size for path in paths)}
    result['notes'] = []
    for path in sorted(root.glob('*.misq')):
        try:
            result['notes'].append(note_audit(path))
        except (AssertionError, ValueError, KeyError, struct.error) as error:
            result['notes'].append({'path': str(path), 'error': type(error).__name__ + ': ' + str(error)})
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--recovery-inventory', type=Path, help='Existing ls -ln inventory of recovery blobs')
    args = parser.parse_args()
    print(json.dumps(audit(args.root, args.recovery_inventory), ensure_ascii=False, indent=2))
