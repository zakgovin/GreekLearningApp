#!/usr/bin/env python3
"""Build offline A2 reference data. Requires modern-greek-inflexion 2.0.7 and pymorphy3.
See docs/word-details.ru.md for sources, attribution and the dependency compatibility patch.
"""
import json,re,hashlib,unicodedata
from pathlib import Path
from collections import defaultdict,Counter
from modern_greek_inflexion import Noun,Adjective,Verb
import pymorphy3
ROOT=Path(__file__).resolve().parents[1]
WORDS=json.loads((ROOT/'app/src/main/assets/a2.json').read_text())['words']
RU=pymorphy3.MorphAnalyzer()

def tokens(s):return re.findall(r'[α-ωάέήίόύώϊΐϋΰ]+',s.lower())
def clean(s):return unicodedata.normalize('NFC',s).strip()
def pick(forms):
    # Prefer short contemporary variants: γράφουμε instead of γράφομε; no learned final -ν.
    forms=set(forms or [])
    if forms-{'ήτο','ήσαν','ήμαστε','ήσαστε'}:forms-={'ήτο','ήσαν','ήμαστε','ήσαστε'}
    f=sorted(forms,key=lambda x:(x.endswith('ομε'),x.endswith('ουνε') or x.endswith('ανε'),len(x),x))
    return f[0] if f else '—'
def ru_words(s):return {RU.parse(x)[0].normal_form for x in re.findall('[а-яё]+',s.lower())}
ART={'ο':('masc','ο','οι'),'η':('fem','η','οι'),'το':('neut','το','τα'),'οι':('masc_pl','ο','οι'),'τα':('neut_pl','το','τα'),'ο/η':('masc','ο','οι')}
ARTICLES={'masc':{'sg':['ο','του','τον',''],'pl':['οι','των','τους','']},'fem':{'sg':['η','της','την',''],'pl':['οι','των','τις','']},'neut':{'sg':['το','του','το',''],'pl':['τα','των','τα','']}}
CASES=['nom','gen','acc','voc'];PEOPLE=[('sg','pri'),('sg','sec'),('sg','ter'),('pl','pri'),('pl','sec'),('pl','ter')]
LABELS=['εγώ · я','εσύ · ты','αυτός/αυτή/αυτό · он/она/оно','εμείς · мы','εσείς · вы','αυτοί/αυτές/αυτά · они']
ERRORS={}

def noun_table(w):
    g=ART.get(w.get('article',''),('neut','',''))[0]
    if w.get('article')=='οι':g=None
    phrase=w['greek'];parts=phrase.split();head=0
    # Compound noun phrases: inflect head noun; inflect agreeing adjective when known.
    if len(parts)>1:
        candidates=[(i,x) for i,x in enumerate(parts) if any(q['greek']==x and q['partOfSpeech']=='noun' for q in WORDS)]
        head=candidates[0][0] if candidates else 0
        if parts[0] in ('αγγλική','αραβική','γαλλική','γερμανική','ελληνική','ιταλική','ισπανική','ξένη','ρωσική','τουρκική'):head=1
    modifiers={'αγγλική':'αγγλικός','γαλλική':'γαλλικός','γερμανική':'γερμανικός','ελληνική':'ελληνικός',
      'αεροπορικό':'αεροπορικός','αθλητικά':'αθλητικός','αθλητικοί':'αθλητικός','απλό':'απλός','αρχαία':'αρχαίος',
      'ασθενής':'ασθενής','βρεφονηπιακός':'βρεφονηπιακός','μητρική':'μητρικός','δημοτικό':'δημοτικός',
      'δραματική':'δραματικός','εμπορικό':'εμπορικός','θεατρικό':'θεατρικός','ζωντανή':'ζωντανός',
      'ηλεκτρικά':'ηλεκτρικός','κεφαλαίο':'κεφαλαίος','κεντρικό':'κεντρικός','κινητό':'κινητός','ηλεκτρική':'ηλεκτρικός',
      'λαϊκή':'λαϊκός','λευκός':'λευκός','μεταφορικό':'μεταφορικός','μουσικό':'μουσικός','ξηρός':'ξηρός',
      'οικογενειακό':'οικογενειακός','οικογενειακή':'οικογενειακός','πρώτες':'πρώτος','ραδιοφωνικός':'ραδιοφωνικός',
      'ραδιοφωνική':'ραδιοφωνικός','συστημένο':'συστημένος','ταξιδιωτικό':'ταξιδιωτικός','αστυνομική':'αστυνομικός',
      'αστυνομικό':'αστυνομικός','υψηλή':'υψηλός','φοιτητική':'φοιτητικός','φυσικό':'φυσικός'}
    if len(parts)>1 and parts[0].lower() in modifiers:head=1
    n=Noun(parts[head],gender=g,proper_name=w.get('properName',False)).all()
    gender=next((x for x in n if g and x.startswith(g.split('_')[0])),next(iter(n)))
    forms=n[gender];out=[]
    for i,case in enumerate(CASES):
        row=[]
        for number in ['sg','pl']:
            if w.get('properName') and number=='pl':row.append('—');continue
            value=pick(forms.get(number,{}).get(case,set()))
            if value!='—':
                pp=parts.copy();pp[head]=value
                for j,x in enumerate(parts):
                    if j==head:continue
                    adj=next((a for a in WORDS if a['partOfSpeech']=='adjective' and (a['greek']==x or x in a.get('inflections',[]))),None)
                    if adj or x.lower() in modifiers:
                        a=Adjective(adj['greek'] if adj else modifiers[x.lower()]).positive_degree()['adj'];pp[j]=pick(a.get(number,{}).get(gender,{}).get(case,set()))
                article=ARTICLES.get(gender,ARTICLES['neut'])[number][i]
                value=' '.join(([article] if article else [])+pp)
            row.append(value)
        out.append([['Именительный · кто? что?','Родительный · кого? чего?','Винительный · кого? что?','Обращение'][i]]+row)
    return {'kind':'noun','headers':['Падеж','Ед. число','Мн. число'],'rows':out,'note':'Без дательного падежа: в современном греческом используются предлоги.'},forms

def grammar(w):
    pos=w['partOfSpeech'];lemma=w['greek']
    try:
        if pos=='noun':return noun_table(w)[0]
        if pos=='adjective':
            a=Adjective(lemma).positive_degree()['adj'];return {'kind':'adjective','headers':['Род','Ед. число','Мн. число'],'rows':[[label,pick(a.get('sg',{}).get(g,{}).get('nom',set())),pick(a.get('pl',{}).get(g,{}).get('nom',set()))] for g,label in [('masc','Мужской · ο'),('fem','Женский · η'),('neut','Средний · το')]],'note':'Именительный падеж. Прилагательное согласуется с существительным в роде и числе.'}
        if pos=='verb':
            v=Verb(lemma);imp=v.create_imperfect_forms();voice='passive' if lemma.endswith(('μαι','μεθα')) and lemma!='είμαι' else 'active'
            present=imp.get('present',{}).get(voice,{}).get('ind',{})
            aor=v.create_aorist().get(voice,{}).get('ind',{})
            conj=v.create_conjunctive().get(voice,{}).get('ind',{})
            past=aor or imp.get('paratatikos',{}).get(voice,{}).get('ind',{})
            future=conj or present
            tenses=[]
            for title,table,prefix in [('Настоящее',present,''),('Прошедшее · αόριστος' if aor else 'Прошедшее · παρατατικός',past,''),('Будущее · θα',future,'θα ')]:
                rows=[]
                for (number,person),label in zip(PEOPLE,LABELS):
                    f=pick(table.get(number,{}).get(person,set()));rows.append([label,(prefix+f) if f!='—' else f])
                tenses.append({'title':title,'headers':['Лицо','Форма'],'rows':rows})
            return {'kind':'verb','tenses':tenses,'note':'Прошедшее — завершённое действие; будущее — простое. Для глаголов без этих форм показано доступное время. Знак — означает, что форма не употребляется.'}
    except Exception as e:ERRORS[w['id']]=str(e)
    return {'kind':'other','note':'Это выражение или неизменяемая единица. Формы базового глоссария: '+', '.join(w.get('inflections',[]) or [lemma])}

def all_strings(obj):
    if isinstance(obj,str):yield obj
    elif isinstance(obj,dict):
        for v in obj.values():yield from all_strings(v)
    elif isinstance(obj,list):
        for v in obj:yield from all_strings(v)

EDITORIAL=json.loads((ROOT/'data/word-details/editorial-examples.json').read_text())
def authored(w):
    l=w['greek'];ru=w['russian'][0];p=w['partOfSpeech'];art=w.get('article','')
    if l in EDITORIAL:
        el,r=EDITORIAL[l]
    elif p=='noun':
        plural=art in ('οι','τα');art='ο' if art=='ο/η' else art
        demonstrative={'ο':'Αυτός','η':'Αυτή','το':'Αυτό','οι':'Αυτοί','τα':'Αυτά'}.get(art,'Αυτό')
        el=demonstrative+' είναι '+(art+' ' if art else '')+l+'.';r='Это '+ru+'.'
    elif p=='adjective':el='Είναι '+l+'.';r='Он '+ru+'.'
    elif p=='verb':
        if l in ['βρέχει','αστράφτει','χιονίζει']:
            el='Σήμερα '+l+'.';r={'βρέχει':'Сегодня идёт дождь.','αστράφτει':'Сегодня сверкает молния.','χιονίζει':'Сегодня идёт снег.'}[l]
        else:
            parsed=RU.parse(ru.split()[0])[0];f=parsed.inflect({'1per','sing','pres'})
            if not f:f=parsed.inflect({'1per','sing','futr'})
            if f:el='Εγώ '+l+'.';r='Я '+f.word+(' '+' '.join(ru.split()[1:]) if len(ru.split())>1 else '')+'.'
            else:el=l;r=ru
    elif l.startswith(('δικός ','δική ','δικό ','δικοί ','δικές ','δικά ')):
        first=l.split()[0];art,noun,ru_noun={'δικός':('Ο','κήπος','Сад'),'δική':('Η','τσάντα','Сумка'),'δικό':('Το','σπίτι','Дом'),'δικοί':('Οι','κήποι','Сады'),'δικές':('Οι','τσάντες','Сумки'),'δικά':('Τα','σπίτια','Дома')}[first]
        el=f'{art} {noun} είναι {l}.';r=f'{ru_noun} {ru}.'
    elif p=='numeral':
        if l.endswith(('ος','ός')):el=f'Είναι ο {l} στη σειρά.';r=f'Он {ru} по порядку.'
        else:el=f'Έχω {l} ευρώ.';r=f'У меня {ru} евро.'
    else:el=l+('' if l.endswith(('!',';','?','.')) else '.');r=ru+('' if ru.endswith(('!','?','.')) else '.')
    return {'greek':el,'russian':r,'source':'Учебный пример','url':''}

def main():
    details={};index=defaultdict(set);lexicon=set()
    phrase_forms={}
    for i,w in enumerate(WORDS):
        gr=grammar(w);details[w['id']]={'grammar':gr,'examples':[]}
        phrase_forms[i]=[' '.join(tokens(t)) for t in all_strings(gr) if len(tokens(t))>1]+[' '.join(tokens(w['greek']))]
        forms=set(tokens(w['greek']))
        for s in all_strings(gr):
            for t in tokens(s):forms.add(t)
        for form in forms:index[form].add(i)
        lexicon.update(forms)
    corpus=[]
    for split in ['test','dev']:
        for line_id,line in enumerate((ROOT/f'data/word-details/tatoeba-{split}.tsv').read_text().splitlines(),1):
            cols=line.split('\t')
            if len(cols)!=4:continue
            el,ru=cols[2:];tt=tokens(el)
            if not 2<=len(tt)<=9 or len(el)>100 or len(ru)>140:continue
            if sum(t not in lexicon for t in tt)>1:continue
            if re.search(r'убий|убить|суицид|секс|порно|наркот|ненавиж|расист|Tom|Mary',ru,re.I):continue
            corpus.append((len(tt)+sum(t not in lexicon for t in tt)*3,el,ru,split,line_id,tt))
    for _,el,ru,split,line_id,tt in sorted(corpus):
        candidates=set().union(*(index[t] for t in tt));rul=ru_words(ru)
        for i in candidates:
            w=WORDS[i];d=details[w['id']]
            if len(tokens(w['greek']))>1 and not any(' '+f+' ' in ' '+' '.join(tt)+' ' for f in phrase_forms[i]):continue
            if len(d['examples'])>=3:continue
            # A Greek inflection alone can be homonymous. Require the Russian sense too.
            meanings=set().union(*(ru_words(r) for r in w['russian']))
            if not meanings.intersection(rul):continue
            if w['partOfSpeech'] in ['adverb','pronoun','preposition','conjunction','particle','article'] and w['greek'].lower() not in tt:continue
            if any(x['greek']==el for x in d['examples']):continue
            d['examples'].append({'greek':el,'russian':ru,'source':'Tatoeba · готовый пример','url':f'https://github.com/Helsinki-NLP/Tatoeba-Challenge/blob/master/data/release/{split}/v2023-09-26/tatoeba-{split}-v2023-09-26.ell-rus.txt.gz','line':line_id})
    sourced=sum(bool(x['examples']) for x in details.values())
    for w in WORDS:
        if not details[w['id']]['examples']:details[w['id']]['examples']=[authored(w)]
    report={'words':len(details),'readyExamplesWords':sourced,'grammarKinds':dict(Counter(x['grammar']['kind'] for x in details.values())),'errors':ERRORS}
    (ROOT/'data/word-details/coverage.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    (ROOT/'app/src/main/assets/word-details.json').write_text(json.dumps({'version':1,'words':details},ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
