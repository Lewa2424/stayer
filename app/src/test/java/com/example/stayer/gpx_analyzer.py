import xml.etree.ElementTree as ET
import math
import sys

def haversine(lat1, lon1, lat2, lon2):
    R = 6371000
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = math.sin(delta_phi / 2.0)**2 + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0)**2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return R * c

gpx_file = r'e:\Programming\Kotlin_projects\Stayer\app\src\test\java\com\example\stayer\stayer_track_20260302_065722.gpx'

print("Parsing:", gpx_file)
try:
    tree = ET.parse(gpx_file)
    root = tree.getroot()
    ns = {'gpx': 'http://www.topografix.com/GPX/1/1'}
    pts = root.findall('.//gpx:trkpt', ns)
    if not pts:
        ns = {'gpx': 'http://www.topografix.com/GPX/1/0'}
        pts = root.findall('.//gpx:trkpt', ns)
    if not pts:
        ns = {'gpx': ''}
        pts = root.findall('.//trkpt', ns)
        
    print(f'Total points found: {len(pts)}')
    
    accepted_dist = 0.0
    rejected = 0
    reasons = {}
    last_accepted = None
    
    for pt in pts:
        desc = pt.find('gpx:desc', ns)
        if desc is None: desc = pt.find('desc', ns)
        
        is_rejected = desc is not None and desc.text and 'REJECTED' in desc.text
        
        if is_rejected:
            rejected += 1
            reason = desc.text.split('.')[0] if '.' in desc.text else desc.text
            reasons[reason] = reasons.get(reason, 0) + 1
        else:
            lat = float(pt.get('lat'))
            lon = float(pt.get('lon'))
            if last_accepted:
                accepted_dist += haversine(last_accepted[0], last_accepted[1], lat, lon)
            last_accepted = (lat, lon)
            
    print(f'Rejected points: {rejected}')
    for r, count in reasons.items():
        print(f'  {r}: {count}')
        
    print(f'Calculated Accepted Distance: {accepted_dist / 1000.0:.3f} km')
except Exception as e:
    print('Error:', e)
