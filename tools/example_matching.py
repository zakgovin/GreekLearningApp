"""Lexical matching for examples; headers, articles and person labels are never evidence."""
import re,unicodedata
ARTICLES={'ο','η','το','οι','τα','τον','την','του','της','των','τους','τις'}
def greek_tokens(s):
 return tuple(re.findall(r'[α-ωάέήίόύώϊΐϋΰ]+',unicodedata.normalize('NFC',s).lower()))
def lexical_forms(word,grammar):
 strings=[word['greek']]+word.get('forms',[])+word.get('inflections',[])
 for row in grammar.get('rows',[]):strings.extend(row[1:])
 for tense in grammar.get('tenses',[]):
  for row in tense.get('rows',[]):strings.extend(row[1:])
 forms=set()
 for string in strings:
  form=greek_tokens(string)
  if word['partOfSpeech']=='noun':
   while len(form)>1 and form[0] in ARTICLES:form=form[1:]
  if word['partOfSpeech']=='verb' and len(form)>1 and form[0]=='θα':form=form[1:]
  if form:
   forms.add(form)
   if word['partOfSpeech']=='noun' and len(form)==2 and word['greek'].split()[-1] in {'μητρική','ηλεκτρική','θεατρικό','αστυνομική','αστυνομικό'}:forms.add(form[::-1])
 if word['partOfSpeech']=='verb' and word['greek'].endswith('άω'):forms.add(greek_tokens(word['greek'][:-1]+'ει'))
 return forms

def matches_example(word,grammar,sentence):
 tokens=greek_tokens(sentence)
 return any(tokens[i:i+len(form)]==form for form in lexical_forms(word,grammar) for i in range(len(tokens)-len(form)+1))
