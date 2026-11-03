"""Corpus checks, independent of Android. Run from any working directory."""
from pathlib import Path
from collections import Counter
import csv
import hashlib
import json
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class A2DictionaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source=json.loads((ROOT/'data/klik-a2.review.json').read_text())
        cls.asset=json.loads((ROOT/'app/src/main/assets/a2.json').read_text())
        cls.report=json.loads((ROOT/'data/klik-a2/coverage.json').read_text())
        cls.words=cls.asset['words']
        cls.by_id={w['id']:w for w in cls.words}

    def test_every_source_entry_has_translation_and_classification(self):
        rows=list(csv.reader((ROOT/'data/klik-a2/ru.tsv').read_text().splitlines(),delimiter='\t'))
        self.assertEqual(list(range(1,1706)),[int(row[0]) for row in rows])
        allowed={'noun','verb','adjective','adverb','pronoun','numeral','phrase','preposition','conjunction','particle','article','heading'}
        for i,pos,ru in rows:
            self.assertIn(pos,allowed)
            self.assertRegex(ru,r'[А-Яа-яЁё]')
            self.assertTrue(all(part.strip() for part in ru.split(';')))
        self.assertEqual(1,sum(row[1]=='heading' for row in rows))

    def test_source_coverage_is_complete_and_no_word_is_orphaned(self):
        original={e['candidateId'] for e in self.source['entries']}
        covered={e['candidateId'] for e in self.report['coverage']}
        excluded={e['candidateId'] for e in self.report['excluded']}
        self.assertEqual(original,covered|excluded)
        self.assertFalse(covered&excluded)
        self.assertEqual(1704,len(covered))
        referenced=set()
        for item in self.report['coverage']:
            self.assertTrue(item['wordIds'])
            for identity in item['wordIds']:
                word=self.by_id[identity]
                self.assertIn(item['candidateId'],word['sourceEntries'])
                self.assertIn(item['page'],word['sourcePages'])
                referenced.add(identity)
        self.assertEqual(set(self.by_id)-set(self.report['starterExtras']),referenced)

    def test_all_starter_ids_and_gender_specific_friend_words_are_preserved(self):
        starter=json.loads((ROOT/'app/src/main/assets/starter.json').read_text())['words']
        for word in starter:
            self.assertIn(word['id'],self.by_id)
            new=self.by_id[word['id']]
            self.assertEqual(word['greek'],new['greek'])
            self.assertEqual(word['partOfSpeech'],new['partOfSpeech'])
        self.assertEqual(['друг'],self.by_id['starter-noun-006']['russian'])
        self.assertEqual(['подруга'],self.by_id['starter-noun-007']['russian'])

    def test_headwords_are_expanded_not_raw_pdf_patterns(self):
        headwords={w['greek'] for w in self.words}
        self.assertTrue({'αγαπημένος','φοιτήτρια','συλλυπητήρια','ελαφρύς','ωστόσο'}<=headwords)
        self.assertNotIn('“Thank you!”]',headwords)
        self.assertNotIn('O, o',headwords)
        for word in self.words:
            self.assertNotRegex(word['greek'],r'[\[\]()/]')
            self.assertNotRegex(word['greek'],r'(^-|\s-|-$)')
            self.assertRegex(word['greek'],r'[Α-Ωα-ωάέήίόύώϊϋΐΰ]')
            self.assertTrue(word['russian'])
        english=next(w for w in self.words if w['greek']=='αγγλική γλώσσα')
        self.assertIn('αγγλικά',english['forms'])

    def test_data_version_is_content_addressed(self):
        words_json=json.dumps(self.words,ensure_ascii=False,sort_keys=True)
        expected='klik-a2-ru-1-'+hashlib.sha256(words_json.encode()).hexdigest()[:12]
        self.assertEqual(expected,self.asset['dictionaryVersion'])
        self.assertEqual(expected,(ROOT/'app/src/main/assets/a2-version.txt').read_text().strip())
        self.assertEqual(self.source['sourceSha256'],self.asset['sourceSha256'])

    def test_proper_category_refinement_keeps_all_existing_ids(self):
        import sys
        sys.path.insert(0,str(ROOT/'tools'))
        from build_a2_dictionary import identity
        registry=json.loads((ROOT/'data/klik-a2/stable-ids.json').read_text())
        for w in self.words:
            self.assertEqual(w['id'],registry[identity(w['greek'],w['partOfSpeech'],w.get('article',''),w.get('properName',False))])
        self.assertEqual(105,sum(w.get('properName',False) for w in self.words))
        for greek in ['Καμάρα','Ναβαρίνου','Πλατεία Συντάγματος','ΟΤΕ']:
            self.assertTrue(next(w for w in self.words if w['greek']==greek)['properName'])

    def test_unique_ids_and_count_report(self):
        self.assertEqual(len(self.words),len(self.by_id))
        self.assertEqual(len(self.words),self.report['wordCount'])
        self.assertEqual(dict(Counter(w['partOfSpeech'] for w in self.words)),self.report['partsOfSpeech'])
        self.assertEqual(3,len(self.report['starterExtras']))
        for word in self.words:
            self.assertEqual(len(word['russian']),len(set(word['russian'])))
            self.assertTrue(word['id'].startswith(('klik-a2-','starter-')))


if __name__=='__main__': unittest.main(verbosity=2)
