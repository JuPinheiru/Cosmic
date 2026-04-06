from flask import Flask, jsonify, request, send_from_directory
import xml.etree.ElementTree as ET
import subprocess
import os
import glob
import mysql.connector

app = Flask(__name__)

# ── Caminhos ──────────────────────────────────────────────────────────────────
BASE_DIR      = r'C:\Users\Julio\Documents\Servers\Server Maplestory'
SERVER_WZ_DIR = os.path.join(BASE_DIR, r'Meu Server\wz')
CLIENT_WZ_DIR = r'C:\Nexon\MapleStory'
WZ_EDITOR_EXE = os.path.join(BASE_DIR, r'Editores\WzEditor.exe')
HTML_DIR      = os.path.dirname(os.path.abspath(__file__))

DB_CONFIG = {
    'host':     'localhost',
    'user':     'root',
    'password': 'test',
    'database': 'cosmic'
}

# ── Cache ─────────────────────────────────────────────────────────────────────
_cache = {}

_item_names_cache = None

def get_db():
    return mysql.connector.connect(**DB_CONFIG)

def get_item_names_map():
    global _item_names_cache
    if _item_names_cache is not None:
        return _item_names_cache
    db = get_db()
    cursor = db.cursor(dictionary=True)
    cursor.execute("SELECT itemid, name, category FROM item_names")
    _item_names_cache = {r['itemid']: {'name': r['name'], 'category': r['category']} for r in cursor.fetchall()}
    cursor.close()
    db.close()
    return _item_names_cache

def get_cache(key, path):
    mtime = os.path.getmtime(path) if os.path.isfile(path) else 0
    if key in _cache and _cache[key]['mtime'] == mtime:
        return _cache[key]['data']
    return None

def set_cache(key, path, data):
    mtime = os.path.getmtime(path) if os.path.isfile(path) else 0
    _cache[key] = {'mtime': mtime, 'data': data}

# ── Helpers ───────────────────────────────────────────────────────────────────
def sxml(wz, img):
    return os.path.join(SERVER_WZ_DIR, wz, img)

def cwz(wz):
    return os.path.join(CLIENT_WZ_DIR, wz)

def parse_xml(path):
    return ET.parse(path)

def find_int_props(node):
    """Retorna dict de todas as propriedades int/float/string de um nó."""
    result = {}
    for child in node:
        name = child.get('name')
        val  = child.get('value')
        tag  = child.tag
        if name and val is not None and tag in ('int', 'float', 'short', 'long', 'string'):
            result[name] = {'value': val, 'type': tag}
    return result

def wz_edit_client(wz_name, node_path, value):
    """Edita nó no .wz binário do client via WzEditor."""
    wz_path = cwz(wz_name)
    if not os.path.exists(WZ_EDITOR_EXE) or not os.path.exists(wz_path):
        return False, 'WzEditor ou WZ não encontrado'
    r = subprocess.run([WZ_EDITOR_EXE, wz_path, node_path, str(value)], capture_output=True, text=True)
    if r.returncode != 0:
        return False, r.stderr.strip()
    return True, None

def read_string_map(img_xml_path):
    """Lê arquivo String.wz/*.img.xml e retorna {id: name}."""
    cached = get_cache('str_' + img_xml_path, img_xml_path)
    if cached: return cached
    result = {}
    try:
        tree = ET.parse(img_xml_path)
        root = tree.getroot()
        for node in root:
            nid = node.get('name', '').lstrip('0') or '0'
            name_el = node.find("./string[@name='name']")
            if name_el is not None:
                result[nid] = name_el.get('value', '')
    except: pass
    set_cache('str_' + img_xml_path, img_xml_path, result)
    return result

# ── Páginas HTML ──────────────────────────────────────────────────────────────
@app.route('/')
def index():
    return '''<!DOCTYPE html><html><head><meta charset="UTF-8"><title>WZ Editor</title>
<style>*{box-sizing:border-box;margin:0;padding:0}body{background:#1a1a2e;color:#eee;font-family:Arial,sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;flex-direction:column;gap:20px}
h1{color:#e94560;font-size:28px}
.cards{display:flex;gap:20px}
.card{background:#16213e;border:1px solid #333;border-radius:12px;padding:30px 40px;text-align:center;cursor:pointer;text-decoration:none;color:#eee;transition:all 0.2s}
.card:hover{border-color:#e94560;transform:scale(1.05)}
.card h2{font-size:18px;color:#e94560;margin-bottom:8px}
.card p{font-size:12px;color:#aaa}
</style></head><body>
<h1>⚔ WZ Editor</h1>
<div class="cards">
  <a class="card" href="/skills"><h2>🔮 Skills</h2><p>Dano, MP, cooldown por nível</p></a>
  <a class="card" href="/mobs"><h2>👾 Mobs</h2><p>HP, EXP, PADamage, elemento</p></a>
  <a class="card" href="/equips"><h2>🎽 Equips</h2><p>Stats, requisitos, slots</p></a>
  <a class="card" href="/cashshop"><h2>💰 CashShop</h2><p>Preço, período, OnSale</p></a>
</div>
</body></html>'''

@app.route('/skills')
def page_skills():
    return send_from_directory(HTML_DIR, 'editor_skills.html')

@app.route('/mobs')
def page_mobs():
    return send_from_directory(HTML_DIR, 'editor_mobs.html')

@app.route('/equips')
def page_equips():
    return send_from_directory(HTML_DIR, 'editor_equips.html')

@app.route('/cashshop')
def page_cashshop():
    return send_from_directory(HTML_DIR, 'cashshop.html')

# ══════════════════════════════════════════════════════════════════════════════
# SKILLS
# ══════════════════════════════════════════════════════════════════════════════
SKILL_EDITABLE = ['mpCon', 'hpCon', 'damage', 'fixdamage', 'mastery', 'attackCount',
                  'mobCount', 'range', 'cooltime', 'time', 'prop', 'x', 'y', 'z',
                  'speed', 'jump', 'hp', 'mp', 'pad', 'mad', 'pdd', 'mdd', 'acc',
                  'eva', 'knockback', 'lt', 'rb', 'bulletCount', 'bulletConsume']

def get_skill_name_map():
    path = sxml('String.wz', 'Skill.img.xml')
    cached = get_cache('skill_names', path)
    if cached: return cached
    result = {}
    try:
        tree = ET.parse(path)
        root = tree.getroot()
        for node in root:
            skill_id = node.get('name', '').lstrip('0') or '0'
            name_el = node.find("./string[@name='name']")
            if name_el is not None:
                result[skill_id] = name_el.get('value', '')
    except: pass
    set_cache('skill_names', path, result)
    return result

@app.route('/api/skills/jobs')
def api_skills_jobs():
    """Lista todos os jobs/arquivos de skill disponíveis."""
    skill_dir = os.path.join(SERVER_WZ_DIR, 'Skill.wz')
    jobs = []
    job_names = {
        '000': 'Beginner', '100': 'Warrior', '110': 'Fighter', '111': 'Crusader', '112': 'Hero',
        '120': 'Page', '121': 'White Knight', '122': 'Paladin',
        '130': 'Spearman', '131': 'Dragon Knight', '132': 'Dark Knight',
        '200': 'Magician', '210': 'F/P Wizard', '211': 'F/P Mage', '212': 'F/P Arch Mage',
        '220': 'I/L Wizard', '221': 'I/L Mage', '222': 'I/L Arch Mage',
        '230': 'Cleric', '231': 'Priest', '232': 'Bishop',
        '300': 'Archer', '310': 'Hunter', '311': 'Ranger', '312': 'Bowmaster',
        '320': 'Crossbowman', '321': 'Sniper', '322': 'Marksman',
        '400': 'Rogue', '410': 'Assassin', '411': 'Hermit', '412': 'Night Lord',
        '420': 'Bandit', '421': 'Chief Bandit', '422': 'Shadower',
        '500': 'Pirate', '510': 'Brawler', '511': 'Marauder', '512': 'Buccaneer',
        '520': 'Gunslinger', '521': 'Outlaw', '522': 'Corsair',
        '900': 'GM', '910': 'SuperGM'
    }
    for f in sorted(os.listdir(skill_dir)):
        if f.endswith('.img.xml'):
            job_id = f.replace('.img.xml', '')
            jobs.append({'id': job_id, 'name': job_names.get(job_id, 'Job ' + job_id)})
    return jsonify({'jobs': jobs})

@app.route('/api/skills/<job_id>')
def api_skills_by_job(job_id):
    """Retorna todas as skills de um job com seus níveis editáveis."""
    xml_path = sxml('Skill.wz', f'{job_id}.img.xml')
    if not os.path.exists(xml_path):
        return jsonify({'error': 'Job não encontrado'}), 404

    cached = get_cache(f'skill_{job_id}', xml_path)
    if cached: return jsonify({'skills': cached})

    name_map = get_skill_name_map()
    tree = ET.parse(xml_path)
    root = tree.getroot()
    skill_node = root.find("imgdir[@name='skill']")
    if skill_node is None:
        return jsonify({'skills': []})

    skills = []
    for skill in skill_node:
        skill_id = skill.get('name', '')
        sid_stripped = skill_id.lstrip('0') or '0'
        skill_name = name_map.get(sid_stripped, 'Skill ' + skill_id)

        level_node = skill.find("imgdir[@name='level']")
        levels = []
        if level_node is not None:
            for lvl in level_node:
                lvl_num = lvl.get('name', '')
                props = {}
                for child in lvl:
                    n = child.get('name')
                    v = child.get('value')
                    t = child.tag
                    if n and v is not None and t in ('int', 'float', 'short', 'string') and n in SKILL_EDITABLE:
                        props[n] = {'value': v, 'type': t}
                if props:
                    levels.append({'level': lvl_num, 'props': props})

        if levels:
            skills.append({'id': skill_id, 'name': skill_name, 'levels': levels})

    set_cache(f'skill_{job_id}', xml_path, skills)
    return jsonify({'skills': skills})

@app.route('/api/skills/update', methods=['POST'])
def api_skills_update():
    """Atualiza um campo de skill em um nível específico."""
    data = request.json
    job_id   = data['job_id']
    skill_id = data['skill_id']
    level    = data['level']
    field    = data['field']
    value    = data['value']

    xml_path = sxml('Skill.wz', f'{job_id}.img.xml')
    if not os.path.exists(xml_path):
        return jsonify({'error': 'Arquivo não encontrado'}), 404

    tree = ET.parse(xml_path)
    root = tree.getroot()

    # Navega até skill/skill_id/level/N/field
    target = root.find(f"imgdir[@name='skill']/imgdir[@name='{skill_id}']/imgdir[@name='level']/imgdir[@name='{level}']")
    if target is None:
        return jsonify({'error': 'Nível não encontrado'}), 404

    prop = target.find(f"*[@name='{field}']")
    if prop is None:
        return jsonify({'error': f'Campo {field} não encontrado'}), 404

    prop.set('value', str(value))
    tree.write(xml_path, encoding='unicode', xml_declaration=True)

    # Invalida cache
    _cache.pop(f'skill_{job_id}', None)

    return jsonify({'ok': True, 'client_synced': False})

# ══════════════════════════════════════════════════════════════════════════════
# MOBS
# ══════════════════════════════════════════════════════════════════════════════
MOB_EDITABLE = ['level', 'maxHP', 'maxMP', 'exp', 'PADamage', 'MADamage',
                'PDDamage', 'MDDamage', 'acc', 'eva', 'speed', 'pushed',
                'undead', 'boss', 'bodyAttack', 'summonType']

def get_mob_name_map():
    path = sxml('String.wz', 'Mob.img.xml')
    cached = get_cache('mob_names', path)
    if cached: return cached
    result = read_string_map(path)
    set_cache('mob_names', path, result)
    return result

@app.route('/api/mobs/search')
def api_mobs_search():
    q = request.args.get('q', '').strip().lower()
    if not q or len(q) < 2:
        return jsonify({'mobs': []})

    name_map = get_mob_name_map()
    results = []

    # Busca por nome
    for mob_id, name in name_map.items():
        if q in name.lower() or q in mob_id:
            results.append({'id': mob_id, 'name': name})
        if len(results) >= 30:
            break

    results.sort(key=lambda x: x['name'])
    return jsonify({'mobs': results})

@app.route('/api/mobs/<mob_id>')
def api_mob_detail(mob_id):
    """Retorna os campos editáveis de um mob."""
    mob_file = mob_id.zfill(7) + '.img.xml'
    xml_path = sxml('Mob.wz', mob_file)
    if not os.path.exists(xml_path):
        return jsonify({'error': 'Mob não encontrado'}), 404

    cached = get_cache(f'mob_{mob_id}', xml_path)
    if cached: return jsonify(cached)

    tree = ET.parse(xml_path)
    root = tree.getroot()
    info = root.find("imgdir[@name='info']")
    if info is None:
        return jsonify({'error': 'info não encontrado'}), 404

    props = {}
    for child in info:
        n = child.get('name')
        v = child.get('value')
        t = child.tag
        if n and v is not None and t in ('int', 'float', 'short') and n in MOB_EDITABLE:
            props[n] = {'value': v, 'type': t}

    name_map = get_mob_name_map()
    name = name_map.get(mob_id.lstrip('0') or '0', 'Mob ' + mob_id)
    result = {'id': mob_id, 'name': name, 'props': props}
    set_cache(f'mob_{mob_id}', xml_path, result)
    return jsonify(result)

@app.route('/api/mobs/update', methods=['POST'])
def api_mobs_update():
    data   = request.json
    mob_id = data['mob_id']
    field  = data['field']
    value  = data['value']

    mob_file = mob_id.zfill(7) + '.img.xml'
    xml_path = sxml('Mob.wz', mob_file)
    if not os.path.exists(xml_path):
        return jsonify({'error': 'Arquivo não encontrado'}), 404

    tree = ET.parse(xml_path)
    root = tree.getroot()
    info = root.find("imgdir[@name='info']")
    if info is None:
        return jsonify({'error': 'info não encontrado'}), 404

    prop = info.find(f"*[@name='{field}']")
    if prop is None:
        return jsonify({'error': f'Campo {field} não encontrado'}), 404

    prop.set('value', str(value))
    tree.write(xml_path, encoding='unicode', xml_declaration=True)
    _cache.pop(f'mob_{mob_id}', None)

    return jsonify({'ok': True})

# ══════════════════════════════════════════════════════════════════════════════
# EQUIPS
# ══════════════════════════════════════════════════════════════════════════════
EQUIP_EDITABLE = ['reqLevel', 'reqJob', 'reqSTR', 'reqDEX', 'reqINT', 'reqLUK',
                  'incSTR', 'incDEX', 'incINT', 'incLUK', 'incPAD', 'incMAD',
                  'incPDD', 'incMDD', 'incACC', 'incEVA', 'incMHP', 'incMMP',
                  'incSpeed', 'incJump', 'tuc', 'attackSpeed', 'cash']

EQUIP_CATEGORIES = ['Cap', 'Cape', 'Coat', 'Glove', 'Longcoat', 'Pants',
                    'Ring', 'Shield', 'Shoes', 'Accessory', 'Weapon']

def get_equip_name_map():
    path = sxml('String.wz', 'Eqp.img.xml')
    cached = get_cache('equip_names', path)
    if cached: return cached
    result = {}
    try:
        tree = ET.parse(path)
        root = tree.getroot()
        # Eqp.img.xml tem estrutura: Eqp > categoria > item
        for cat_node in root:
            for item_node in cat_node:
                item_id = item_node.get('name', '').lstrip('0') or '0'
                name_el = item_node.find("./string[@name='name']")
                if name_el is not None:
                    result[item_id] = name_el.get('value', '')
    except: pass
    set_cache('equip_names', path, result)
    return result

@app.route('/api/equips/categories')
def api_equip_categories():
    cats = []
    char_dir = os.path.join(SERVER_WZ_DIR, 'Character.wz')
    for cat in sorted(os.listdir(char_dir)):
        cat_path = os.path.join(char_dir, cat)
        if os.path.isdir(cat_path):
            cats.append(cat)
    return jsonify({'categories': cats})

@app.route('/api/equips/search')
def api_equips_search():
    q   = request.args.get('q', '').strip().lower()
    cat = request.args.get('cat', '')
    if not q or len(q) < 2:
        return jsonify({'equips': []})

    name_map = get_equip_name_map()
    results  = []

    for item_id, name in name_map.items():
        if q in name.lower() or q in item_id:
            # Descobre a categoria pelo prefixo do itemid
            results.append({'id': item_id, 'name': name})
        if len(results) >= 30:
            break

    results.sort(key=lambda x: x['name'])
    return jsonify({'equips': results})

def find_equip_xml(item_id):
    """Acha o arquivo XML de um equip pelo item_id."""
    item_id_padded = item_id.zfill(8)
    char_dir = os.path.join(SERVER_WZ_DIR, 'Character.wz')
    # Procura em todas as categorias
    for cat in os.listdir(char_dir):
        cat_path = os.path.join(char_dir, cat)
        if not os.path.isdir(cat_path):
            continue
        xml_path = os.path.join(cat_path, item_id_padded + '.img.xml')
        if os.path.exists(xml_path):
            return xml_path, cat
    return None, None

@app.route('/api/equips/<item_id>')
def api_equip_detail(item_id):
    xml_path, category = find_equip_xml(item_id)
    if not xml_path:
        return jsonify({'error': 'Equip não encontrado'}), 404

    cached = get_cache(f'equip_{item_id}', xml_path)
    if cached: return jsonify(cached)

    tree = ET.parse(xml_path)
    root = tree.getroot()
    info = root.find("imgdir[@name='info']")
    if info is None:
        return jsonify({'error': 'info não encontrado'}), 404

    props = {}
    for child in info:
        n = child.get('name')
        v = child.get('value')
        t = child.tag
        if n and v is not None and t in ('int', 'float', 'short', 'string') and n in EQUIP_EDITABLE:
            props[n] = {'value': v, 'type': t}

    name_map = get_equip_name_map()
    name = name_map.get(item_id.lstrip('0') or '0', 'Item ' + item_id)
    result = {'id': item_id, 'name': name, 'category': category, 'props': props}
    set_cache(f'equip_{item_id}', xml_path, result)
    return jsonify(result)

@app.route('/api/equips/update', methods=['POST'])
def api_equips_update():
    data    = request.json
    item_id = data['item_id']
    field   = data['field']
    value   = data['value']

    xml_path, _ = find_equip_xml(item_id)
    if not xml_path:
        return jsonify({'error': 'Arquivo não encontrado'}), 404

    tree = ET.parse(xml_path)
    root = tree.getroot()
    info = root.find("imgdir[@name='info']")
    if info is None:
        return jsonify({'error': 'info não encontrado'}), 404

    prop = info.find(f"*[@name='{field}']")
    if prop is None:
        return jsonify({'error': f'Campo {field} não encontrado'}), 404

    prop.set('value', str(value))
    tree.write(xml_path, encoding='unicode', xml_declaration=True)
    _cache.pop(f'equip_{item_id}', None)

    return jsonify({'ok': True})


# ══════════════════════════════════════════════════════════════════════════════
# CASHSHOP (Commodity)
# ══════════════════════════════════════════════════════════════════════════════
COMMODITY_XML = sxml('Etc.wz', 'Commodity.img.xml')
COMMODITY_WZ  = cwz('Etc.wz')

_commodity_cache = None
_commodity_mtime = None

def parse_commodity():
    global _commodity_cache, _commodity_mtime
    mtime = os.path.getmtime(COMMODITY_XML)
    if _commodity_cache is not None and mtime == _commodity_mtime:
        tree = ET.parse(COMMODITY_XML)
        root = tree.getroot()
        return tree, root, list(_commodity_cache)
    tree = ET.parse(COMMODITY_XML)
    root = tree.getroot()
    items = []
    for child in root:
        entry = {p.get('name'): p.get('value') for p in child if p.get('name')}
        if 'SN' in entry and 'ItemId' in entry:
            items.append({
                'sn':      int(entry.get('SN', 0)),
                'itemId':  int(entry.get('ItemId', 0)),
                'price':   int(entry.get('Price', 0)),
                'period':  int(entry.get('Period', 90)),
                'count':   int(entry.get('Count', 1)),
                'onSale':  entry.get('OnSale', '0') == '1',
                'gender':  entry.get('Gender', '-1'),
                'priority': int(entry.get('Priority', 1)),
            })
    _commodity_cache = items
    _commodity_mtime = mtime
    return tree, root, items

@app.route('/api/commodity/items')
def api_commodity_items():
    try:
        _, _, items = parse_commodity()
        names = get_item_names_map()
        for it in items:
            info = names.get(it['itemId'])
            it['name']     = info['name']     if info else 'Item ' + str(it['itemId'])
            it['category'] = info['category'] if info else 'Other'
        items.sort(key=lambda x: x['itemId'])
        return jsonify({'items': items})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/commodity/update', methods=['POST'])
def api_commodity_update():
    try:
        data = request.json
        sn   = int(data['sn'])
        tree, root, _ = parse_commodity()
        updated = False
        for child in root:
            sn_prop = child.find(".//*[@name='SN']")
            if sn_prop is not None and int(sn_prop.get('value', -1)) == sn:
                for prop in child:
                    n = prop.get('name')
                    if n == 'Price'  and 'price'  in data: prop.set('value', str(int(data['price'])))
                    if n == 'Period' and 'period' in data: prop.set('value', str(int(data['period'])))
                    if n == 'OnSale' and 'onSale' in data: prop.set('value', '1' if data['onSale'] else '0')
                    if n == 'Count'  and 'count'  in data: prop.set('value', str(int(data['count'])))
                updated = True
                break
        if not updated:
            return jsonify({'error': 'SN not found'}), 404
        tree.write(COMMODITY_XML, encoding='unicode', xml_declaration=False)
        client_synced = False
        if os.path.exists(WZ_EDITOR_EXE) and os.path.exists(COMMODITY_WZ):
            try:
                errors = []
                if 'price'  in data:
                    r = subprocess.run([WZ_EDITOR_EXE, COMMODITY_WZ, 'commodity-sn', str(sn), 'Price',  str(int(data['price']))],  capture_output=True, text=True)
                    if r.returncode != 0: errors.append(r.stderr)
                if 'period' in data:
                    r = subprocess.run([WZ_EDITOR_EXE, COMMODITY_WZ, 'commodity-sn', str(sn), 'Period', str(int(data['period']))], capture_output=True, text=True)
                    if r.returncode != 0: errors.append(r.stderr)
                if 'onSale' in data:
                    r = subprocess.run([WZ_EDITOR_EXE, COMMODITY_WZ, 'commodity-sn', str(sn), 'OnSale', '1' if data['onSale'] else '0'], capture_output=True, text=True)
                    if r.returncode != 0: errors.append(r.stderr)
                if 'count'  in data:
                    r = subprocess.run([WZ_EDITOR_EXE, COMMODITY_WZ, 'commodity-sn', str(sn), 'Count',  str(int(data['count']))],  capture_output=True, text=True)
                    if r.returncode != 0: errors.append(r.stderr)
                if errors:
                    return jsonify({'ok': True, 'client_synced': False, 'client_error': ' | '.join(errors)})
                client_synced = True
            except Exception as e:
                return jsonify({'ok': True, 'client_synced': False, 'client_error': str(e)})
        return jsonify({'ok': True, 'client_synced': client_synced})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/commodity/add', methods=['POST'])
def api_commodity_add():
    try:
        data = request.json
        tree, root, items = parse_commodity()
        max_sn  = max((it['sn'] for it in items), default=0)
        new_sn  = max_sn + 1
        new_node = ET.SubElement(root, 'imgdir', name=str(new_sn))
        fields = [
            ('SN',       str(new_sn)),
            ('ItemId',   str(int(data['itemId']))),
            ('Price',    str(int(data.get('price', 0)))),
            ('Period',   str(int(data.get('period', 90)))),
            ('Count',    str(int(data.get('count', 1)))),
            ('OnSale',   '1' if data.get('onSale', True) else '0'),
            ('Priority', str(int(data.get('priority', 1)))),
            ('Gender',   str(data.get('gender', -1))),
        ]
        for fname, fval in fields:
            ET.SubElement(new_node, 'int', name=fname, value=fval)
        ET.indent(tree, space='\t')
        tree.write(COMMODITY_XML, encoding='unicode', xml_declaration=False)
        names = get_item_names_map()
        info  = names.get(int(data['itemId']))
        return jsonify({'ok': True, 'sn': new_sn,
            'name':     info['name']     if info else 'Item ' + str(data['itemId']),
            'category': info['category'] if info else 'Other'})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/commodity/remove', methods=['POST'])
def api_commodity_remove():
    try:
        sn = int(request.json['sn'])
        tree, root, _ = parse_commodity()
        to_remove = None
        for child in root:
            sn_prop = child.find(".//*[@name='SN']")
            if sn_prop is not None and int(sn_prop.get('value', -1)) == sn:
                to_remove = child
                break
        if to_remove is None:
            return jsonify({'error': 'SN not found'}), 404
        root.remove(to_remove)
        tree.write(COMMODITY_XML, encoding='unicode', xml_declaration=False)
        return jsonify({'ok': True})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/search_item')
def api_search_item():
    q = request.args.get('q', '').strip().lower()
    try:
        names = get_item_names_map()
        results = []
        for itemid, info in names.items():
            if q in info['name'].lower() or q in str(itemid):
                results.append({'itemId': itemid, 'name': info['name'], 'category': info['category']})
                if len(results) >= 40: break
        results.sort(key=lambda x: x['name'])
        return jsonify({'results': results})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/wz_status')
def api_wz_status():
    return jsonify({
        'wz_editor_available': os.path.exists(WZ_EDITOR_EXE),
        'client_wz_dir': CLIENT_WZ_DIR,
        'server_wz_dir': SERVER_WZ_DIR,
    })

@app.route('/api/cache/clear', methods=['POST'])
def api_clear_cache():
    global _item_names_cache, _commodity_cache
    _item_names_cache = None
    _commodity_cache = None
    _cache.clear()
    return jsonify({'ok': True})

if __name__ == '__main__':
    app.run(host='100.114.6.22', port=5001, debug=True)
