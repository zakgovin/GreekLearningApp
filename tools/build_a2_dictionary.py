"""Build a versioned Russian KLIK A2 lexicon with complete source-entry coverage.
Russian meanings and POS are editorial annotations, not an official Russian edition.
"""
from pathlib import Path
import csv
import hashlib
import json
import re
import unicodedata as U
from collections import Counter

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'data/klik-a2.review.json'
ANNOTATIONS = ROOT / 'data/klik-a2/ru.tsv'
ASSET = ROOT / 'app/src/main/assets/a2.json'
# Source typography sometimes mixes Latin and Greek lookalikes.
# Apply only actual lookalikes encountered, not transliteration.
LOOKALIKES = str.maketrans({'A':'Α','B':'Β','E':'Ε','Z':'Ζ','H':'Η','I':'Ι','K':'Κ','M':'Μ','N':'Ν','O':'Ο','P':'Ρ','T':'Τ','X':'Χ','Y':'Υ','a':'α','o':'ο'})
ARTICLES = r'(?:ο/η|η/ο|το|τα|οι|ο|η)'


def clean(text):
    return U.normalize('NFC', re.sub(r'\s+', ' ', text.replace('\u00ad','')).strip())


def without_notes(raw):
    return clean(re.sub(r'\[[^\]]*\]', '', raw).translate(LOOKALIKES))


def identity(greek, pos, article, proper):
    return '|'.join((U.normalize('NFC', greek).casefold(), pos, article, str(proper)))


def expand_compact(raw):
    """Expand shared-stem endings. Other genders are references, not default spelling answers."""
    raw = clean(raw)
    match = re.match(r'^([^\s,]+?)-([^\s,]+)(.*)$', raw)
    if not match:
        return None
    stem, ending, rest = match.groups()
    primary = stem + ending
    variants = [primary]
    for ending in re.findall(r'(?:,|/)\s*-?([Α-Ωα-ωάέήίόύώϊϋΐΰ]+)',rest):
        variants.append(stem+ending)
    return primary, list(dict.fromkeys(variants))


# Corrections verified against the supplied PDF and Greek morphology. Indices refer to the frozen extraction.
OVERRIDES = {
    115: [('από μέσα','',[],[],'изнутри'),('από έξω','',[],[],'снаружи')],
    464: [('ελαφρύς','',[],['ελαφριά','ελαφρύ'])],
    554: [('ευρωπαϊκός','',[],['ευρωπαϊκή','ευρωπαϊκό'])],
    1117: [('παντρεμένος','',[],['παντρεμένη','παντρεμένο'])],
    1137: [('παραπάνω','',['πιο πάνω'],[])],
    1655: [('χλιαρός','',[],['χλιαρή','χλιαρό'])],
    7: [('αγγλική γλώσσα','η',['αγγλικά'],[])],
    27: [('ΑΕΙ','το',[],[])], 53: [('στις δέκα ακριβώς','', ['στις 10 ακριβώς'],[])],
    58: [('Αλέξανδρος ο Μέγας','ο',[],[])],
    93: [('άνεργος','',[],['άνεργη','άνεργο']),('άνεργος','ο',[],[],'безработный','noun'),('άνεργη','η',[],[],'безработная','noun')],
    129: [('μου αρέσει','',['αρέσει'],[])],
    155: [('ΤΕΙ','το',[],[])],156: [('ΑΤΜ','το',[],[])],
    162: [('αυτοί','',['αυτές','αυτά'],[])],
    164: [('αυτός','',[],[],'он'),('αυτή','',[],[],'она'),('αυτό','',[],[],'оно')],
    165: [('αυτός','',[],[],'этот'),('αυτή','',[],[],'эта'),('αυτό','',[],[],'это')],
    188: [('εβδομάδα','η',['βδομάδα'],[])],
    228: [('γαλλική γλώσσα','η',['γαλλικά'],[])],
    245: [('γερμανική γλώσσα','η',['γερμανικά'],[])],
    303: [('γυρίζω','',['γυρνάω','γυρνώ'],[])],
    314: [('δεκατέσσερα','',['δεκατέσσερις'],[])],
    316: [('δεκατρία','',['δεκατρείς'],[])],
    318: [('ΔΕΗ','',[],[])],320: [('ΔΕΠΑ','',[],[])],
    381: [],382: [],383: [],384: [],385: [],386: [],387: [],388: [],
    389: [('δίκλινο','το',['δίκλινο δωμάτιο'],[])],
    397: [('ΔΟΥ','',[],[])], 439: [('ειδικός','ο/η',[],[])],
    456: [('εκείνος','',[],[],'тот'),('εκείνη','',[],[],'та'),('εκείνο','',[],[],'то')],
    469: [('ελληνική γλώσσα','η',['ελληνικά'],[])],472: [('ΕΛΤΑ','',[],[])],
    476: [('ελεύθερος','',[],['ελεύθερη'])],
    483: [('ένα','',['ένας','μία','μια'],[])],
    484: [('ένας','',['μια','μία','ένα'],[])],
    488: [('ενδιαφέρων','',[],['ενδιαφέρουσα','ενδιαφέρον'])],
    489: [('εννέα','',['εννιά'],[])],540: [('εσύ','',['εσείς'],[])],
    547: [('ΕΥΑΘ','',[],[])],549: [('ΕΥΔΑΠ','',[],[])],
    594: [('ηλικιωμένος','',[],['ηλικιωμένη','ηλικιωμένο'])],
    677: [('κανένας','',['κανείς'],['καμία','καμιά','κανένα'])],
    678: [('κανένας','',['κανείς'],['καμία','καμιά','κανένα'])],
    684: [('κάποιος','',[],['κάποια'])],685: [('κάποιος','',[],['κάποια','κάποιο'])],
    731: [('ΚΕΠ','το',[],[])],761: [('κοιτώ','',['κοιτάζω'],[])],
    781: [('κοτόπουλο','το',[],[],'цыплёнок;курятина'),('κότα','η',[],[],'курица')],
    807: [('κάνει κρύο','',['έχει κρύο'],[])],
    824: [('λάθος','',[],[])],
    849: [('για λίγο','',[],[])],850: [('σε λίγο','',[],[])],
    854: [('Αβραάμ Λίνκολν','ο',[],[])],
    866: [('μ.μ.','',[],[])],
    957: [('μονόκλινο','το',['μονόκλινο δωμάτιο'],[])],
    1034: [('ξανθός','',[],['ξανθή','ξανθιά','ξανθό'])],
    1054: [('Ο Θάνατος του εμποράκου','',[],[])],1055: [('ΟΑΣΘ','',[],[])],
    1061: [('ο','',['η','το'],[])],
    1087: [('ΟΤΕ','',[],[])],1095: [('π.μ.','',[],[])],
    1124: [('παραγγέλνω','',['παραγγέλλω'],[])],
    1130: [('Σας παρακαλώ!','',[],[])],1131: [('Παρακαλώ!','',[],[])],
    1155: [('δεν πειράζει','',[],[])],
    1204: [('πληροφορία','η',[],['πληροφορίες'])],
    1215: [('ποιανού','',[],['ποιανής'])],
    1217: [('ποιον','',['ποια'],[])],
    1218: [('ποιος','',[],['ποια','ποιο'])],1219: [('ποιος','',['ποια','ποιο'],[])],
    1225: [('πολλοί','',['πολλές','πολλά'],[])],
    1230: [('πολύς','',[],['πολλή','πολύ'])],
    1243: [('πόσοι','',['πόσες','πόσα'],[])],
    1251: [('Από πού είσαι;','',['Από πού είστε;'],[])],
    1264: [('πριν από λίγο','',['πριν από λίγο καιρό'],[])],
    1293: [('πρωινό','το',['πρωινό γεύμα'],[])],
    1304: [('Πώς σε λένε;','',['Πώς σας λένε;'],[])],
    1319: [('Ρωμαίος και Ιουλιέτα','',[],[])],
    1341: [('σερβιτόρος','ο',[],[],'официант'),('σερβιτόρα','η',[],[],'официантка')],
    1346: [('σημειώνω','',['κρατάω σημειώσεις','κρατώ σημειώσεις'],[])],
    1368: [('σκούφος','ο',[],[]),('σκουφί','το',[],[])],
    1371: [('σόδα','η',['μαγειρική σόδα'],[])],
    1372: [('σοκολάτα','η',[],[],'шоколад'),('σοκολατάκι','το',[],[],'шоколадная конфета')],
    1402: [('συγγραφέας','ο/η',[],[])],
    1413: [('συλλυπητήρια','τα',[],[])],
    1432: [('στη συνέχεια','',[],[])],
    1457: [('σχολείο','το',[],[],'школа'),('σχολή','η',[],[],'училище;школа')],
    1490: [('τέσσερα','',['τέσσερις'],[])],
    1505: [('τηλεφωνώ','',['παίρνω τηλέφωνο'],[])],
    1524: [('τρία','',['τρεις'],[])],
    1589: [('φίλος','ο',[],[],'друг'),('φίλη','η',[],[],'подруга')],
    1594: [('φοιτητής','ο',[],[],'студент'),('φοιτήτρια','η',[],[],'студентка')],
    1659: [('χορευτής','ο',[],[],'танцор'),('χορεύτρια','η',[],[],'танцовщица')],
    1670: [('χρόνος','ο',[],['χρόνια'])],
    1676: [('χτες','',['χθες'],[])],
    1683: [('χωρισμένος','',[],['χωρισμένη','χωρισμένο'])],
    1685: [('ψάρι','το',[],['ψάρια'])],
}
for index, owner, russian in [(381,'μου',['мой','моя','моё','мои','мои','мои']),
                              (382,'σου',['твой','твоя','твоё','твои','твои','твои']),
                              (383,'του',['его']*6),(384,'της',['её']*6),(385,'του',['его']*6),
                              (386,'μας',['наш','наша','наше','наши','наши','наши']),
                              (387,'σας',['ваш','ваша','ваше','ваши','ваши','ваши']),
                              (388,'τους',['их']*6)]:
    OVERRIDES[index] = [(f'{form} {owner}','',[],[],ru) for form,ru in zip(['δικός','δική','δικό','δικοί','δικές','δικά'],russian)]
# Source 382 includes the polite/plural σας, separately represented by 387.
OVERRIDES[382] += OVERRIDES[387]


def shapes(index, entry, pos, translations):
    if index in OVERRIDES:
        return OVERRIDES[index]
    raw = without_notes(entry['greekRaw'])
    raw = raw.replace('«','').replace('»','')
    if pos == 'noun':
        # Common-gender nouns are one lexeme; explicit male/female nouns are separate lexemes.
        common = re.match(r'^(.*?),\s*ο/η\s*$',raw)
        if common:
            return [(common[1],'ο/η',[],[])]
        terms = re.findall(r'([^,/]+?),\s*('+ARTICLES+r')(?=\s|/|$)',raw)
        if terms:
            out=[]
            for i,(term,article) in enumerate(terms):
                term = term.strip(' /')
                term = re.sub(r'\s*\([^)]*\)','',term).strip()
                if not term:
                    continue
                ru = translations[i] if len(terms)==2 and len(translations)==2 else ';'.join(translations)
                # Each gender gets its own unambiguous Russian label when present.
                out.append((term,article,[],[],ru))
            return out
        # Acronym expansions and grammatical parentheticals are explanatory notes.
        raw = re.sub(r'\s*\([^)]*\)','',raw)
        return [(raw.rstrip(', '),'',[],[])]
    compact = expand_compact(raw)
    if compact:
        primary, variants = compact
        return [(primary,'',[],variants[1:])]
    if pos == 'verb':
        contracted = re.match(r'^(.+?)(άω),\s*-ώ$',raw)
        if contracted:
            return [(contracted[1]+'άω','',[contracted[1]+'ώ'],[])]
        variants = [clean(x) for x in re.split(r'\s*/\s*',raw)]
        return [(variants[0],'',variants[1:],[])]
    raw = re.sub(r'\(([^)]*)\)',r'\1',raw)
    return [(clean(raw).rstrip(', '),'',[],[])]


def build():
    source=json.loads(SOURCE.read_text())
    entries=source['entries']
    rows={}
    for index,pos,russian in csv.reader(ANNOTATIONS.read_text().splitlines(),delimiter='\t'):
        i=int(index);assert i not in rows
        rows[i]=(pos,[clean(x) for x in russian.split(';') if x.strip()])
    assert set(rows)==set(range(1,len(entries)+1))
    starter=json.loads((ROOT/'app/src/main/assets/starter.json').read_text())['words']
    registry=json.loads((ROOT/'data/klik-a2/stable-ids.json').read_text())
    properEntries=set(json.loads((ROOT/'data/klik-a2/proper-name-entries.json').read_text()))
    legacy={identity(w['greek'],w['partOfSpeech'],w.get('article',''),False):w for w in starter}
    words={};coverage=[]; excluded=[]
    for i,entry in enumerate(entries,1):
        pos,translations=rows[i]
        if pos=='heading':
            excluded.append({'entryIndex':i,'candidateId':entry['candidateId'],'reason':'alphabet heading, not a lexical entry'})
            continue
        resultIds=[]
        for shaped in shapes(i,entry,pos,translations):
            greek,article,forms,inflections,*specific=shaped
            russian=specific[0].split(';') if specific else translations
            wordPos=specific[1] if len(specific)>1 else pos
            greek=clean(greek);article=clean(article)
            proper=wordPos=='noun' and i in properEntries
            # Preserve the existing starter spelling/ID whenever this is the same lexeme.
            candidates=[greek]+forms
            old=next((legacy[identity(x,wordPos,article,False)] for x in candidates if identity(x,wordPos,article,False) in legacy),None)
            if old:
                forms=list(dict.fromkeys([greek]+forms+old.get('forms',[])))
                greek=old['greek'];forms=[x for x in forms if x!=greek]
                russian=list(dict.fromkeys(old['russian']+russian))
            key=identity(greek,wordPos,article,proper)
            wordId=old['id'] if old else registry.get(key,'klik-a2-'+hashlib.sha256(key.encode()).hexdigest()[:20])
            if key not in words:
                words[key]={'id':wordId,'greek':greek,'russian':[], 'partOfSpeech':wordPos,'article':article,'forms':[],
                            'inflections':[], 'source':'klik-a2-v1','sourceEntries':[],'sourcePages':[], 'english':[],'properName':proper}
            word=words[key]
            for field,values in [('russian',russian),('forms',forms),('inflections',inflections),('sourceEntries',[entry['candidateId']]),('sourcePages',[entry['sourcePage']]),('english',[{1130:'Please!',1131:"You are welcome!"}.get(i,entry['englishRaw'])])]:
                word[field]=list(dict.fromkeys(word[field]+values))
            resultIds.append(word['id'])
        assert resultIds,(i,entry)
        coverage.append({'entryIndex':i,'candidateId':entry['candidateId'],'wordIds':list(dict.fromkeys(resultIds)), 'page':entry['sourcePage']})
    allwords=list(words.values())
    ids={w['id'] for w in allwords}
    missing=[w for w in starter if w['id'] not in ids]
    # Starter extras stay available and keep their IDs, but aren't falsely attributed to the PDF.
    allwords+=missing
    assert len({w['id'] for w in allwords})==len(allwords)
    for w in allwords:
        assert w['greek'] and w['russian'] and all(x.strip() for x in w['russian'])
        assert not any(c in w['greek'] for c in '[]()/'), w
        assert ',' not in w['greek'] or w['greek']=='ό,τι',w
        assert not re.search(r'(^-|\s-|-$)',w['greek']),w

    version='klik-a2-ru-1-'+hashlib.sha256(json.dumps(allwords,ensure_ascii=False,sort_keys=True).encode()).hexdigest()[:12]
    result={'schemaVersion':1,'dictionaryVersion':version,'sourceSha256':source['sourceSha256'],
            'translationNote':'Русские переводы и грамматическая разметка — редакционная подготовка приложения; не официальное русское издание.',
            'words':allwords}
    (ASSET.parent/'a2-version.txt').write_text(version+'\n')
    ASSET.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    report={'rawCandidateCount':len(entries),'lexicalEntryCount':len(coverage),'excluded':excluded,
            'wordCount':len(allwords),'a2WordCount':len(words),'starterExtras':[w['id'] for w in missing],
            'partsOfSpeech':dict(Counter(w['partOfSpeech'] for w in allwords)),
            'categories':dict(Counter('proper_noun' if w.get('properName') else w['partOfSpeech'] for w in allwords)),
            'coverage':coverage,'sourceSha256':source['sourceSha256'],
            'translationStatus':'editorial Russian translations; not independently certified',
            'normalizationOverrides':sorted(OVERRIDES)}
    (ROOT/'data/klik-a2/coverage.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print({k:v for k,v in report.items() if k!='coverage'})


if __name__=='__main__': build()
