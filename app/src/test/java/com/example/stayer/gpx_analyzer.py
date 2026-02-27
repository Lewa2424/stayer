import xml.etree.ElementTree as ET
from math import radians, cos, sin, asin, sqrt

def haversine(lon1, lat1, lon2, lat2):
    lon1, lat1, lon2, lat2 = map(radians, [lon1, lat1, lon2, lat2])
    dlon = lon2 - lon1 
    dlat = lat2 - lat1 
    a = sin(dlat/2)**2 + cos(lat1) * cos(lat2) * sin(dlon/2)**2
    c = 2 * asin(sqrt(a)) 
    r = 6371 
    return c * r * 1000 

def analyze_gpx(filepath):
    tree = ET.parse(filepath)
    root = tree.getroot()
    
    # Handle namespaces
    namespace = ''
    if '}' in root.tag:
        namespace = root.tag.split('}')[0] + '}'

    trkpts = root.findall(f'.//{namespace}trkpt')
    
    print(f"Found {len(trkpts)} track points.")

    total_dist = 0.0
    prev_pt = None

    for pt in trkpts:
        lat = float(pt.get('lat'))
        lon = float(pt.get('lon'))
        
        if prev_pt:
            dist = haversine(prev_pt[0], prev_pt[1], lon, lat)
            total_dist += dist
            
        prev_pt = (lon, lat)

    print(f"Total Raw Distance: {total_dist:.2f} meters")

if __name__ == '__main__':
    analyze_gpx(r'e:\\Programming\\Kotlin_projects\\Stayer\\app\\src\\test\\java\\com\\example\\stayer\\stayer_track_20260227_184058.gpx')
