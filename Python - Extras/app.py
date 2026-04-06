from flask import Flask, jsonify, send_from_directory
from flask_sock import Sock
import mysql.connector
import os
import json
import threading
import time

app = Flask(__name__)
sock = Sock(app)

DB_CONFIG = {
    'host': 'localhost',
    'user': 'root',
    'password': 'test',
    'database': 'cosmic'
}

def get_db():
    return mysql.connector.connect(**DB_CONFIG)

def categorize(item_id):
    prefix = item_id // 10000
    if 130 <= prefix <= 170:
        return 'Weapon'
    elif prefix in (105, 106, 110):
        return 'Coat' if prefix == 105 else ('Pants' if prefix == 106 else 'Shield')
    elif prefix in (107,):
        return 'Shoes'
    elif prefix in (108,):
        return 'Glove'
    elif prefix in (109,):
        return 'Cape'
    elif 100 <= prefix <= 104:
        return 'Cap'
    elif prefix in (111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129):
        return 'Accessory'
    return 'other'

@app.route('/')
def index():
    return send_from_directory(os.path.dirname(os.path.abspath(__file__)), 'index.html')

@app.route('/api/collection')
def api_collection():
    db = get_db()
    cursor = db.cursor(dictionary=True)
    cursor.execute("""
        SELECT DISTINCT d.itemid, COALESCE(n.name, CONCAT('Item ', d.itemid)) as name,
               COALESCE(n.category, 'other') as category
        FROM drop_data d
        LEFT JOIN item_names n ON n.itemid = d.itemid
        WHERE d.itemid >= 1000000 AND d.itemid < 2000000
        ORDER BY d.itemid ASC
    """)
    items = []
    for row in cursor.fetchall():
        item_id = row['itemid']
        cat = row['category']
        if cat == 'other':
            cat = categorize(item_id)
        if cat == 'other':
            continue
        items.append({'id': item_id, 'name': row['name'], 'category': cat})
               
    cursor.execute("SET SESSION group_concat_max_len = 100000")
    cursor.execute("""
        SELECT a.id, COALESCE(a.account_name, a.nick, CONCAT('Account ', a.id)) as name, a.collectionBonus,
               GROUP_CONCAT(ac.itemid) as items
        FROM accounts a
        LEFT JOIN account_collection ac ON ac.accountid = a.id
        GROUP BY a.id ORDER BY a.nick
    """)
    accounts = []
    for acc in cursor.fetchall():
        item_list = [int(x) for x in acc['items'].split(',') if x.strip()] if acc['items'] else []
        accounts.append({
            'id': acc['id'],
            'name': acc['name'] or 'Account ' + str(acc['id']),
            'bonus': acc['collectionBonus'] or 0,
            'collected': len(item_list),
            'items': item_list
        })
    cursor.close()
    db.close()
    return jsonify({'items': items, 'accounts': accounts})

@app.route('/api/cards')
def api_cards():
    db = get_db()
    cursor = db.cursor(dictionary=True)
    cursor.execute("SELECT DISTINCT cardid, mobid FROM monstercarddata ORDER BY cardid ASC")
    all_cards = [{'cardid': r['cardid'], 'mobid': r['mobid']} for r in cursor.fetchall()]

    cursor.execute("""
        SELECT c.accountid, mb.cardid, MAX(mb.level) as level
        FROM monsterbook mb
        JOIN characters c ON c.id = mb.charid
        GROUP BY c.accountid, mb.cardid
    """)
    collected_by_account = {}
    for row in cursor.fetchall():
        aid = row['accountid']
        if aid not in collected_by_account:
            collected_by_account[aid] = {}
        collected_by_account[aid][str(row['cardid'])] = row['level']

    cursor.execute("SELECT id, COALESCE(account_name, nick, CONCAT('Account ', id)) as name, cardBookBonusStats, cardBookBonusRate FROM accounts ORDER BY name")
    accounts = []
    for acc in cursor.fetchall():
        aid = acc['id']
        card_data = collected_by_account.get(aid, {})
        accounts.append({
            'id': aid,
            'name': acc['name'] or 'Account ' + str(aid),
            'cards': card_data,
            'total': len(card_data),
            'maxed': sum(1 for v in card_data.values() if v >= 5),
            'cardBookBonusStats': acc['cardBookBonusStats'] or 0,
            'cardBookBonusRate': float(acc['cardBookBonusRate'] or 0)
        })
    cursor.close()
    db.close()
    return jsonify({'cards': all_cards, 'accounts': accounts})

@app.route('/api/mobs')
def api_mobs():
    db = get_db()
    cursor = db.cursor(dictionary=True)
    cursor.execute("SELECT DISTINCT mobid FROM monstercarddata ORDER BY mobid ASC")
    mobs = [{'id': r['mobid'], 'name': 'Mob ' + str(r['mobid']), 'drops': [], 'kills': {}} for r in cursor.fetchall()]

    mob_ids = [m['id'] for m in mobs]
    if mob_ids:
        fmt = ','.join(['%s'] * len(mob_ids))

        # Drops
        cursor.execute("""
            SELECT dropperid as mobid, itemid, minimum_quantity, maximum_quantity, chance
            FROM drop_data WHERE dropperid IN ({}) AND itemid >= 1000000 AND itemid < 2000000
            ORDER BY dropperid, chance DESC
        """.format(fmt), mob_ids)
        drops_by_mob = {}
        for row in cursor.fetchall():
            mid = row['mobid']
            if mid not in drops_by_mob:
                drops_by_mob[mid] = []
            drops_by_mob[mid].append({'itemid': row['itemid'], 'min': row['minimum_quantity'], 'max': row['maximum_quantity'], 'chance': row['chance']})
        for mob in mobs:
            mob['drops'] = drops_by_mob.get(mob['id'], [])

        # Kill counts por conta
        cursor.execute("""
            SELECT mk.accountid, mk.mobid, mk.kills,
                   COALESCE(a.account_name, a.nick, CONCAT('Account ', a.id)) as account_name
            FROM mob_kill_count mk
            JOIN accounts a ON a.id = mk.accountid
            WHERE mk.mobid IN ({})
        """.format(fmt), mob_ids)
        kills_by_mob = {}
        for row in cursor.fetchall():
            mid = row['mobid']
            if mid not in kills_by_mob:
                kills_by_mob[mid] = {}
            kills_by_mob[mid][row['account_name']] = row['kills']
        for mob in mobs:
            mob['kills'] = kills_by_mob.get(mob['id'], {})

    cursor.close()
    db.close()
    return jsonify({'mobs': mobs})


@app.route('/api/quests')
def api_quests():
    db = get_db()
    cursor = db.cursor(dictionary=True)
    cursor.execute("SELECT id, COALESCE(account_name, nick, CONCAT('Account ', id)) as name, questBonusStats, questBonusRate FROM accounts ORDER BY name")
    accounts_raw = cursor.fetchall()
    accounts = []
    for acc in accounts_raw:
        aid = acc['id']
        cursor.execute("""
            SELECT qs.quest, MAX(qs.status) as status
            FROM queststatus qs
            JOIN characters c ON c.id = qs.characterid
            WHERE c.accountid = %s AND qs.quest > 0
            GROUP BY qs.quest ORDER BY qs.quest
        """, (aid,))
        quests_raw = cursor.fetchall()
        completed = []
        in_progress = []
        for q in quests_raw:
            entry = {'id': q['quest'], 'name': 'Quest ' + str(q['quest'])}
            if q['status'] == 2:
                completed.append(entry)
            elif q['status'] == 1:
                in_progress.append(entry)
        accounts.append({
            'id': aid,
            'name': acc['name'],
            'completed': completed,
            'in_progress': in_progress,
            'total_completed': len(completed),
            'total_in_progress': len(in_progress),
            'questBonusStats': acc['questBonusStats'] or 0,
            'questBonusRate': float(acc['questBonusRate'] or 0)
        })
    cursor.close()
    db.close()
    return jsonify({'accounts': accounts})

@app.route('/api/item_sources/<int:item_id>')
def api_item_sources(item_id):
    db = get_db()
    cursor = db.cursor(dictionary=True)
    cursor.execute("""
        SELECT dropperid as mobid, chance, minimum_quantity as min_qty, maximum_quantity as max_qty
        FROM drop_data WHERE itemid = %s ORDER BY chance DESC LIMIT 10
    """, (item_id,))
    drops = [{'mobid': r['mobid'], 'chance': r['chance'], 'min': r['min_qty'], 'max': r['max_qty']} for r in cursor.fetchall()]
    cursor.close()
    db.close()
    return jsonify({'drops': drops})


# ==================== DAMAGE METER ====================

@app.route('/api/damage/sessions')
def api_damage_sessions():
    db = get_db()
    cursor = db.cursor(dictionary=True)
    cursor.execute("""
        SELECT ds.id, ds.characterid, ds.mapid, ds.started_at, ds.ended_at,
               c.name as char_name, c.level, c.job,
               COALESCE(a.account_name, a.nick, CONCAT('Account ', a.id)) as account_name
        FROM damage_sessions ds
        JOIN characters c ON c.id = ds.characterid
        JOIN accounts a ON a.id = ds.accountid
        ORDER BY ds.started_at DESC
        LIMIT 50
    """)
    sessions = []
    for row in cursor.fetchall():
        # Top 3 mobs da sessao
        cursor2 = db.cursor(dictionary=True)
        cursor2.execute("""
            SELECT mobid, COUNT(*) as kills, SUM(damage) as total_dmg
            FROM damage_log
            WHERE session_id = %s AND is_heal=0 AND is_received=0 AND mobid > 0
            GROUP BY mobid ORDER BY total_dmg DESC LIMIT 3
        """, (row['id'],))
        top_mobs = [{'mobid': r['mobid'], 'kills': int(r['kills'])} for r in cursor2.fetchall()]
        cursor2.close()

        sessions.append({
            'id': row['id'],
            'char_name': row['char_name'],
            'level': row['level'],
            'job': row['job'],
            'account_name': row['account_name'],
            'mapid': row['mapid'],
            'started_at': row['started_at'],
            'ended_at': row['ended_at'],
            'active': row['ended_at'] is None,
            'top_mobs': top_mobs
        })
    cursor.close()
    db.close()
    return jsonify({'sessions': sessions})

@app.route('/api/damage/session/<int:session_id>')
def api_damage_session(session_id):
    db = get_db()
    cursor = db.cursor(dictionary=True)

    # Info da sessão
    cursor.execute("""
        SELECT ds.*, c.name as char_name, c.level, c.job,
               COALESCE(a.account_name, a.nick, CONCAT('Account ', a.id)) as account_name
        FROM damage_sessions ds
        JOIN characters c ON c.id = ds.characterid
        JOIN accounts a ON a.id = ds.accountid
        WHERE ds.id = %s
    """, (session_id,))
    session = cursor.fetchone()
    if not session:
        return jsonify({'error': 'Session not found'}), 404

    duration_ms = (session['ended_at'] or int(time.time() * 1000)) - session['started_at']
    duration_s = max(1, duration_ms / 1000)

    # Stats gerais
    cursor.execute("""
        SELECT
            SUM(CASE WHEN is_heal=0 AND is_received=0 THEN damage ELSE 0 END) as total_damage,
            SUM(CASE WHEN is_heal=1 THEN damage ELSE 0 END) as total_heal,
            SUM(CASE WHEN is_received=1 THEN damage ELSE 0 END) as total_received,
            SUM(CASE WHEN is_crit=1 AND is_heal=0 AND is_received=0 THEN 1 ELSE 0 END) as crits,
            COUNT(CASE WHEN is_heal=0 AND is_received=0 THEN 1 END) as hits
        FROM damage_log WHERE session_id = %s
    """, (session_id,))
    stats = cursor.fetchone()

    total_damage = int(stats['total_damage'] or 0)
    hits = int(stats['hits'] or 0)
    crits = int(stats['crits'] or 0)
    crit_pct = round(crits / hits * 100, 1) if hits > 0 else 0
    dps = round(total_damage / duration_s)

    # Dano por skill
    cursor.execute("""
        SELECT skillid,
               SUM(damage) as total,
               COUNT(*) as hits,
               MAX(damage) as max_hit,
               SUM(CASE WHEN is_crit=1 THEN 1 ELSE 0 END) as crits
        FROM damage_log
        WHERE session_id = %s AND is_heal=0 AND is_received=0
        GROUP BY skillid ORDER BY total DESC
    """, (session_id,))
    skills = [{'skillid': r['skillid'], 'total': r['total'], 'hits': r['hits'],
               'max_hit': r['max_hit'], 'crits': r['crits']} for r in cursor.fetchall()]

    # Timeline (dano por segundo agrupado a cada 5s)
    cursor.execute("""
        SELECT FLOOR((timestamp - %s) / 5000) * 5 as t,
               SUM(CASE WHEN is_heal=0 AND is_received=0 THEN damage ELSE 0 END) as dmg
        FROM damage_log WHERE session_id = %s AND is_heal=0 AND is_received=0
        GROUP BY t ORDER BY t
    """, (session['started_at'], session_id))
    timeline = [{'t': int(r['t']), 'dmg': int(r['dmg'])} for r in cursor.fetchall()]

    # Dano por mob
    cursor.execute("""
        SELECT mobid, COUNT(*) as kills,
               SUM(damage) as total_dmg,
               MAX(damage) as max_hit
        FROM damage_log
        WHERE session_id = %s AND is_heal=0 AND is_received=0 AND mobid > 0
        GROUP BY mobid ORDER BY total_dmg DESC
    """, (session_id,))
    mobs_by_id = [{'mobid': r['mobid'], 'kills': int(r['kills']),
                   'total_dmg': int(r['total_dmg'] or 0),
                   'max_hit': int(r['max_hit'] or 0)} for r in cursor.fetchall()]


    cursor.close()
    db.close()

    return jsonify({
        'session': {
            'id': session['id'],
            'char_name': session['char_name'],
            'level': session['level'],
            'job': session['job'],
            'account_name': session['account_name'],
            'mapid': session['mapid'],
            'started_at': session['started_at'],
            'ended_at': session['ended_at'],
            'active': session['ended_at'] is None,
            'duration_s': round(duration_s),
            'total_damage': total_damage,
            'total_heal': stats['total_heal'] or 0,
            'total_received': stats['total_received'] or 0,
            'dps': dps,
            'crit_pct': crit_pct,
            'hits': hits,
            'exp_gained': int(session.get('current_exp') or 0),
            'meso_gained': int(session.get('current_meso') or 0)
        },
        'skills': skills,
        'timeline': timeline,
        'mobs': mobs_by_id
    })

@sock.route('/ws/damage')
def ws_damage(ws):
    """WebSocket que envia updates em tempo real das sessões ativas"""
    last_sent = {}
    while True:
        try:
            db = get_db()
            cursor = db.cursor(dictionary=True)
            cursor.execute("""
                SELECT ds.id, ds.characterid, ds.mapid, ds.started_at,
                       ds.initial_exp, ds.initial_meso, ds.current_exp, ds.current_meso,
                       c.name as char_name, c.level, c.job,
                       COALESCE(a.account_name, a.nick, CONCAT('Account ', a.id)) as account_name,
                       SUM(CASE WHEN dl.is_heal=0 AND dl.is_received=0 THEN dl.damage ELSE 0 END) as total_damage,
                       SUM(CASE WHEN dl.is_heal=1 THEN dl.damage ELSE 0 END) as total_heal,
                       SUM(CASE WHEN dl.is_received=1 THEN dl.damage ELSE 0 END) as total_received,
                       COUNT(CASE WHEN dl.is_heal=0 AND dl.is_received=0 THEN 1 END) as hits,
                       SUM(CASE WHEN dl.is_crit=1 THEN 1 ELSE 0 END) as crits
                FROM damage_sessions ds
                JOIN characters c ON c.id = ds.characterid
                JOIN accounts a ON a.id = ds.accountid
                LEFT JOIN damage_log dl ON dl.session_id = ds.id
                WHERE ds.ended_at IS NULL
                GROUP BY ds.id
            """)
            sessions = []
            now_ms = int(time.time() * 1000)
            for row in cursor.fetchall():
                duration_s = max(1, (now_ms - row['started_at']) / 1000)
                total = int(row['total_damage'] or 0)
                hits = int(row['hits'] or 0)
                crits = int(row['crits'] or 0)
                exp_gained = int(row['current_exp'] or 0)
                meso_gained = int(row['current_meso'] or 0)

                # Mobs por sessao
                cursor2 = db.cursor(dictionary=True)
                cursor2.execute("""
                    SELECT mobid, COUNT(*) as kills, SUM(damage) as total_dmg
                    FROM damage_log
                    WHERE session_id = %s AND is_heal=0 AND is_received=0 AND mobid > 0
                    GROUP BY mobid ORDER BY total_dmg DESC LIMIT 5
                """, (row['id'],))
                mobs = [{'mobid': r['mobid'], 'kills': int(r['kills']), 'dmg': int(r['total_dmg'] or 0)} for r in cursor2.fetchall()]
                cursor2.close()

                sessions.append({
                    'id': row['id'],
                    'char_name': row['char_name'],
                    'level': row['level'],
                    'job': row['job'],
                    'account_name': row['account_name'],
                    'mapid': int(row['mapid'] or 0),
                    'total_damage': total,
                    'total_heal': int(row['total_heal'] or 0),
                    'total_received': int(row['total_received'] or 0),
                    'dps': round(total / duration_s),
                    'crit_pct': round(crits / hits * 100, 1) if hits > 0 else 0,
                    'duration_s': round(duration_s),
                    'exp_gained': exp_gained,
                    'meso_gained': meso_gained,
                    'mobs': mobs
                })
            sessions.sort(key=lambda x: x['total_damage'], reverse=True)
            cursor.close()
            db.close()
            db.close()

            ws.send(json.dumps({'type': 'update', 'sessions': sessions}))
            time.sleep(2)
        except Exception as e:
            break


@app.route('/api/damage/reset', methods=['POST'])
def api_damage_reset():
    db = get_db()
    cursor = db.cursor()
    cursor.execute("UPDATE damage_sessions SET ended_at = %s WHERE ended_at IS NULL", (int(time.time() * 1000),))
    db.commit()
    cursor.close()
    db.close()
    return jsonify({'ok': True})


@app.route('/api/scrolls')
def api_scrolls():
    db = get_db()
    cursor = db.cursor(dictionary=True)
    cursor.execute("SELECT id, COALESCE(account_name, nick, CONCAT('Account ', id)) as name FROM accounts ORDER BY name")
    accounts_raw = cursor.fetchall()
    accounts = []
    for acc in accounts_raw:
        aid = acc['id']
        cursor.execute("""
            SELECT ss.itemid, ss.quantity, COALESCE(n.name, CONCAT('Item ', ss.itemid)) as name
            FROM scroll_stash ss
            LEFT JOIN item_names n ON n.itemid = ss.itemid
            WHERE ss.accountid = %s ORDER BY ss.itemid
        """, (aid,))
        scrolls = [{'itemid': r['itemid'], 'quantity': r['quantity'], 'name': r['name']} for r in cursor.fetchall()]
        total_qty = sum(s['quantity'] for s in scrolls)
        accounts.append({
            'id': aid,
            'name': acc['name'],
            'scrolls': scrolls,
            'total_types': len(scrolls),
            'total_qty': total_qty
        })
    cursor.close()
    db.close()
    return jsonify({'accounts': accounts})

if __name__ == '__main__':
    app.run(host='100.114.6.22', port=5000, debug=True)
