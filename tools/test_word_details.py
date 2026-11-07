#!/usr/bin/env python3
import json,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class DetailsTest(unittest.TestCase):
 def setUp(self):
  self.words=json.loads((ROOT/'app/src/main/assets/a2.json').read_text())['words']
  self.data=json.loads((ROOT/'app/src/main/assets/word-details.json').read_text())['words']
 def entry(self,lemma):return self.data[next(w['id'] for w in self.words if w['greek']==lemma)]
 def test_complete_coverage_and_example_limit(self):
  self.assertEqual({w['id'] for w in self.words},set(self.data))
  for d in self.data.values():
   self.assertTrue(1<=len(d['examples'])<=3)
   for e in d['examples']:self.assertTrue(e['greek'] and e['russian'] and e['source'])
 def test_noun_and_compound_agreement(self):
  self.assertEqual(['Родительный · кого? чего?','του φίλου','των φίλων'],self.entry('φίλος')['grammar']['rows'][1])
  self.assertEqual('της αγγλικής γλώσσας',self.entry('αγγλική γλώσσα')['grammar']['rows'][1][1])
  self.assertEqual('τα κινητά τηλέφωνα',self.entry('κινητό τηλέφωνο')['grammar']['rows'][0][2])
 def test_only_a2_tenses_and_known_irregular(self):
  for w in self.words:
   if w['partOfSpeech']=='verb':
    t=self.data[w['id']]['grammar']['tenses'];self.assertEqual(3,len(t));self.assertTrue(all(len(x['rows'])==6 for x in t))
    self.assertTrue(any(row[1]!='—' for row in t[0]['rows']),w['greek'])
  self.assertEqual('θα είμαι',self.entry('είμαι')['grammar']['tenses'][2]['rows'][0][1])
 def test_corpus_examples_are_exact_quotes(self):
  for d in self.data.values():
   for e in d['examples']:
    if e['url']:
     split='test' if '/test/' in e['url'] else 'dev'
     lines=(ROOT/f'data/word-details/tatoeba-{split}.tsv').read_text().splitlines()
     row=lines[e['line']-1].split('\t');self.assertEqual(row[2:], [e['greek'],e['russian']])
if __name__=='__main__':unittest.main()
