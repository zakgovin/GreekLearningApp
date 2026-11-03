"""Extract review candidates, not production vocabulary. Requires PyMuPDF."""
import argparse
import hashlib
import json
import re
import unicodedata
from pathlib import Path
import fitz


def extract(path):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    records = []
    warnings = []
    with fitz.open(path) as doc:
        for number, page in enumerate(doc, 1):
            if number < 5 or number > 18:
                continue
            columns = [[], [], []]
            for block in page.get_text('dict')['blocks']:
                for line in block.get('lines', []):
                    x, y, _, _ = line['bbox']
                    if not 45 < y < 727:
                        continue
                    text = unicodedata.normalize('NFC', ''.join(s['text'] for s in line['spans'])).strip()
                    if text:
                        columns[0 if x < 200 else 1 if x < 367 else 2].append((y, x, text))
            for column, lines in enumerate(columns, 1):
                lines.sort()
                if not lines:
                    continue
                baseline = min(x for _, x, _ in lines)
                entries = []
                current = []
                for _, x, text in lines:
                    if current and (x < baseline + 8 or ('=' in text and any('=' in part for part in current))):
                        entries.append(' '.join(current))
                        current = []
                    current.append(text)
                if current:
                    entries.append(' '.join(current))
                for index, raw in enumerate(entries, 1):
                    if '=' not in raw:
                        warnings.append({'page': number, 'column': column, 'raw': raw})
                        continue
                    greek, english = [s.strip() for s in raw.split('=', 1)]
                    if re.fullmatch(r'[Α-Ω],\s*[α-ω]', greek):
                        continue  # Alphabet headings are not vocabulary.
                    records.append({
                        'candidateId': f'klik-a2-review-p{number}-c{column}-e{index}',
                        'sourcePage': number, 'sourceColumn': column,
                        'greekRaw': greek, 'englishRaw': english,
                        'translationsRu': [], 'partOfSpeech': 'unknown',
                        'reviewStatus': 'pending',
                    })
    return {'schemaVersion': 1, 'status': 'unreviewed-extraction',
            'sourceSha256': digest, 'languages': ['el', 'en'],
            'note': 'Candidate IDs are extraction coordinates, not stable production word IDs. All entries require review before training.',
            'entryCount': len(records), 'warnings': warnings, 'entries': records}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('pdf', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    result = extract(args.pdf)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f"Candidates: {result['entryCount']}; unmatched blocks: {len(result['warnings'])}")
