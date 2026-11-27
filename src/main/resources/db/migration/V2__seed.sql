-- Synthetic data: 50 carriers, 2,000 loads, ~13k load events.
-- Deterministic: setseed() fixes random(), so every run makes the same carriers,
-- lanes and lateness. Dates are relative to now(), so "in transit" and "late"
-- stay realistic whenever this runs.
-- Run as transport_migrator, in one session (DBeaver: Execute script, Alt+X).

SELECT setseed(0.42);

-- ---------------------------------------------------------------------------
-- Reference data (temporary, gone when the session ends)
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE seed_city (city text PRIMARY KEY, state char(2), lat float8, lon float8);
INSERT INTO seed_city VALUES
  ('Dallas','TX',32.7767,-96.7970),        ('Atlanta','GA',33.7490,-84.3880),
  ('Chicago','IL',41.8781,-87.6298),       ('Los Angeles','CA',34.0522,-118.2437),
  ('Houston','TX',29.7604,-95.3698),       ('Memphis','TN',35.1495,-90.0490),
  ('Denver','CO',39.7392,-104.9903),       ('Phoenix','AZ',33.4484,-112.0740),
  ('Kansas City','MO',39.0997,-94.5786),   ('Columbus','OH',39.9612,-82.9988),
  ('Nashville','TN',36.1627,-86.7816),     ('Charlotte','NC',35.2271,-80.8431),
  ('Seattle','WA',47.6062,-122.3321),      ('Salt Lake City','UT',40.7608,-111.8910),
  ('Laredo','TX',27.5306,-99.4803),        ('Indianapolis','IN',39.7684,-86.1581),
  ('Jacksonville','FL',30.3322,-81.6557),  ('Newark','NJ',40.7357,-74.1724);

CREATE TEMP TABLE seed_lane (lane_no int PRIMARY KEY, origin text, destination text);
INSERT INTO seed_lane VALUES
  ( 1,'Dallas','Atlanta'),        ( 2,'Atlanta','Dallas'),          ( 3,'Chicago','Dallas'),
  ( 4,'Los Angeles','Phoenix'),   ( 5,'Phoenix','Los Angeles'),     ( 6,'Houston','Memphis'),
  ( 7,'Memphis','Chicago'),       ( 8,'Laredo','Dallas'),           ( 9,'Laredo','Chicago'),
  (10,'Denver','Salt Lake City'), (11,'Salt Lake City','Seattle'),  (12,'Seattle','Los Angeles'),
  (13,'Kansas City','Denver'),    (14,'Columbus','Newark'),         (15,'Newark','Charlotte'),
  (16,'Charlotte','Atlanta'),     (17,'Nashville','Columbus'),      (18,'Indianapolis','Nashville'),
  (19,'Jacksonville','Atlanta'),  (20,'Atlanta','Jacksonville'),    (21,'Chicago','Indianapolis'),
  (22,'Houston','Laredo'),        (23,'Dallas','Houston'),          (24,'Los Angeles','Dallas'),
  (25,'Chicago','Columbus'),      (26,'Memphis','Atlanta'),         (27,'Kansas City','Chicago'),
  (28,'Phoenix','Denver'),        (29,'Newark','Chicago'),          (30,'Dallas','Kansas City');

-- Lanes with coordinates and road miles (straight-line distance x 1.2).
CREATE TEMP TABLE seed_lane_geo AS
SELECT l.lane_no,
       o.city AS o_city, o.state AS o_state, o.lat AS o_lat, o.lon AS o_lon,
       d.city AS d_city, d.state AS d_state, d.lat AS d_lat, d.lon AS d_lon,
       round(1.2 * 3959 * 2 * asin(sqrt(
             power(sin(radians(d.lat - o.lat) / 2), 2)
           + cos(radians(o.lat)) * cos(radians(d.lat)) * power(sin(radians(d.lon - o.lon) / 2), 2))))::int AS miles
FROM seed_lane l
JOIN seed_city o ON o.city = l.origin
JOIN seed_city d ON d.city = l.destination;

-- ---------------------------------------------------------------------------
-- Start clean (lets you re-run this script)
-- ---------------------------------------------------------------------------
TRUNCATE transport.load_event, transport.load, transport.carrier RESTART IDENTITY;

-- ---------------------------------------------------------------------------
-- Carriers: every 7th one (ids 7, 14, 21, 28, 35, 42, 49) is a "bad actor"
-- that runs late far more often. Ids 17 and 34 are inactive.
-- ---------------------------------------------------------------------------
INSERT INTO transport.carrier
  (name, mc_number, dot_number, scac, contact_email, phone, home_state, safety_rating, active, created_at)
SELECT
  n.name,
  'MC' || (100000 + i * 7919),
  (2000000 + i * 104729)::text,
  'T' || chr(65 + i / 26) || chr(65 + i % 26) || 'X',
  'dispatch@' || replace(lower(n.name), ' ', '') || '.example',
  '(' || (200 + i * 7) || ') 555-' || lpad((1000 + i * 37)::text, 4, '0'),
  (ARRAY['TX','GA','IL','CA','TN','CO','AZ','MO','OH','NC','WA','UT','IN','FL','NJ'])[1 + i % 15],
  CASE WHEN i = 42         THEN 'UNSATISFACTORY'
       WHEN i % 10 = 3     THEN 'CONDITIONAL'
       WHEN i % 13 = 0     THEN 'UNRATED'
       ELSE 'SATISFACTORY' END,
  i NOT IN (17, 34),
  now() - make_interval(days => 400 + i * 11)
FROM generate_series(1, 50) AS i
CROSS JOIN LATERAL (
  SELECT (ARRAY['Red River','Blue Ridge','Lone Star','Great Plains','Iron Horse','Prairie','Summit',
                'Coastal','Northern','Heartland'])[1 + (i - 1) % 10]
         || ' ' ||
         (ARRAY['Freight','Logistics','Transport','Carriers','Trucking'])[1 + (i - 1) / 10] AS name
) n;

-- ---------------------------------------------------------------------------
-- Loads: one row of random numbers per load, materialized once so every
-- column below sees the same values.
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE seed_load_plan AS
SELECT i,
       1 + floor(random() * 30)::int   AS lane_no,
       1 + floor(random() * 50)::int   AS carrier_pick,
       random() AS r_when,     -- where in the window the pickup falls
       random() AS r_equip,
       random() AS r_weight,
       random() AS r_rate,
       random() AS r_late,     -- decides whether this load runs late
       random() AS r_late_by,  -- how late
       random() AS r_cancel,
       random() AS r_tender,   -- future loads: assigned yet or not
       random() AS r_misc
FROM generate_series(1, 2000) AS i;

CREATE TEMP TABLE seed_load AS
SELECT p.i,
       g.*,
       -- inactive carriers only keep loads from more than 30 days ago
       CASE WHEN p.carrier_pick IN (17, 34)
             AND pickup_at > now() - interval '30 days'
            THEN p.carrier_pick + 1 ELSE p.carrier_pick END       AS carrier_id,
       pickup_at,
       pickup_at + make_interval(hours => (g.miles / 45.0 + 4)::int)  AS delivery_due,
       CASE WHEN p.r_cancel < 0.03 THEN 'CANCELLED' END            AS cancelled,
       -- bad actors run late 45% of the time, everyone else 10%
       CASE WHEN p.r_late < CASE WHEN p.carrier_pick % 7 = 0 THEN 0.45 ELSE 0.10 END
            THEN 2 + p.r_late_by * 34                              -- 2 to 36 hours late
            ELSE -p.r_late_by * 3                                  -- up to 3 hours early
       END                                                          AS late_hours,
       p.r_equip, p.r_weight, p.r_rate, p.r_tender, p.r_misc
FROM seed_load_plan p
JOIN seed_lane_geo g ON g.lane_no = p.lane_no
CROSS JOIN LATERAL (
  -- pickups from 90 days ago to 14 days ahead, on the hour; every 12th load is
  -- picked up in the last 60 hours so there is always freight on the road
  SELECT date_trunc('hour',
           CASE WHEN p.i % 12 = 0 THEN now() - p.r_when * interval '60 hours'
                ELSE now() - interval '90 days' + p.r_when * interval '104 days' END) AS pickup_at
) t;

INSERT INTO transport.load
  (load_number, carrier_id, status, equipment_type, origin_city, origin_state,
   destination_city, destination_state, miles, weight_lbs, rate_usd,
   pickup_scheduled_at, delivery_scheduled_at, picked_up_at, delivered_at, created_at)
SELECT
  'LD-' || lpad(s.i::text, 6, '0'),
  CASE WHEN st.status = 'TENDERED' THEN NULL ELSE s.carrier_id END,
  st.status,
  CASE WHEN s.r_equip < 0.60 THEN 'DRY_VAN' WHEN s.r_equip < 0.85 THEN 'REEFER' ELSE 'FLATBED' END,
  s.o_city, s.o_state, s.d_city, s.d_state, s.miles,
  (8000 + s.r_weight * 37000)::int,
  round((s.miles * (2.00 + s.r_rate * 1.50))::numeric, 2),
  s.pickup_at,
  s.delivery_due,
  CASE WHEN st.status IN ('IN_TRANSIT','DELIVERED') THEN s.picked_up END,
  CASE WHEN st.status = 'DELIVERED' THEN s.actual_delivery END,
  -- created 1 to 5 days before pickup, and never in the future
  least(s.pickup_at - make_interval(hours => 24 + (s.r_misc * 96)::int),
        now() - make_interval(hours => 2 + (s.r_misc * 46)::int))
FROM (
  SELECT s.*,
         s.pickup_at + make_interval(mins => (s.r_misc * 90)::int)        AS picked_up,
         s.delivery_due + make_interval(mins => (s.late_hours * 60)::int) AS actual_delivery
  FROM seed_load s
) s
CROSS JOIN LATERAL (
  SELECT CASE
           WHEN s.cancelled IS NOT NULL                     THEN 'CANCELLED'
           WHEN s.picked_up > now() AND s.r_tender < 0.35
                AND s.pickup_at > now() + interval '2 days' THEN 'TENDERED'
           WHEN s.picked_up > now()                         THEN 'ASSIGNED'
           WHEN s.actual_delivery <= now()                  THEN 'DELIVERED'
           ELSE 'IN_TRANSIT'
         END AS status
) st
ORDER BY s.i;

-- ---------------------------------------------------------------------------
-- Load events, built from each load's own timestamps
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE seed_event_src AS
SELECT l.*, g.o_lat, g.o_lon, g.d_lat, g.d_lon, p.r_misc, p.r_late_by
FROM transport.load l
JOIN seed_load_plan p ON l.load_number = 'LD-' || lpad(p.i::text, 6, '0')
JOIN seed_lane_geo g  ON g.lane_no = p.lane_no;

-- TENDERED: every load, at origin, when it was created
INSERT INTO transport.load_event (load_id, event_type, occurred_at, city, state, latitude, longitude)
SELECT id, 'TENDERED', created_at, origin_city, origin_state, o_lat, o_lon FROM seed_event_src;

-- ASSIGNED: once a carrier accepted it
INSERT INTO transport.load_event (load_id, event_type, occurred_at, notes)
SELECT id, 'ASSIGNED',
       least(created_at + make_interval(hours => 1 + (r_misc * 20)::int),
             created_at + (now() - created_at) / 2,
             coalesce(picked_up_at, 'infinity') - interval '30 minutes'),
       'Carrier accepted tender'
FROM seed_event_src WHERE carrier_id IS NOT NULL;

-- CANCELLED
INSERT INTO transport.load_event (load_id, event_type, occurred_at, notes)
SELECT id, 'CANCELLED',
       greatest(created_at + interval '30 minutes',
                least(pickup_scheduled_at - interval '6 hours', now() - interval '1 hour')),
       'Shipper cancelled'
FROM seed_event_src WHERE status = 'CANCELLED';

-- PICKED_UP
INSERT INTO transport.load_event (load_id, event_type, occurred_at, city, state, latitude, longitude)
SELECT id, 'PICKED_UP', picked_up_at, origin_city, origin_state, o_lat, o_lon
FROM seed_event_src WHERE picked_up_at IS NOT NULL;

-- CHECK_CALL: every 6 hours on the road, position interpolated along the lane
INSERT INTO transport.load_event (load_id, event_type, occurred_at, latitude, longitude, notes)
SELECT e.id, 'CHECK_CALL', e.picked_up_at + make_interval(hours => 6 * n),
       round((e.o_lat + (e.d_lat - e.o_lat) * least(1.0, 6.0 * n / e.road_hours))::numeric, 6),
       round((e.o_lon + (e.d_lon - e.o_lon) * least(1.0, 6.0 * n / e.road_hours))::numeric, 6),
       'Driver check call'
FROM (
  SELECT s.*, extract(epoch FROM (s.delivery_scheduled_at - s.pickup_scheduled_at)) / 3600 AS road_hours
  FROM seed_event_src s WHERE s.picked_up_at IS NOT NULL
) e
CROSS JOIN LATERAL generate_series(1, 8) AS n
WHERE e.picked_up_at + make_interval(hours => 6 * n)
      < least(coalesce(e.delivered_at, now()), now()) - interval '1 hour';

-- DELAY_REPORTED: most late loads (80%) get a reported reason
INSERT INTO transport.load_event (load_id, event_type, occurred_at, delay_reason, notes)
SELECT id, 'DELAY_REPORTED',
       greatest(picked_up_at + interval '1 hour',
                least(delivery_scheduled_at - interval '3 hours', now() - interval '10 minutes')),
       (ARRAY['WEATHER','MECHANICAL','TRAFFIC','SHIPPER_DELAY','HOURS_OF_SERVICE'])[1 + (id % 5)::int],
       'Driver reported delay'
FROM seed_event_src
WHERE picked_up_at IS NOT NULL
  AND coalesce(delivered_at, now()) > delivery_scheduled_at + interval '2 hours'
  AND r_misc < 0.80;

-- EXCEPTION: a handful of delivered loads arrive damaged
INSERT INTO transport.load_event (load_id, event_type, occurred_at, city, state, delay_reason, notes)
SELECT id, 'EXCEPTION', delivered_at - interval '20 minutes', destination_city, destination_state,
       'DAMAGE', 'Consignee noted damaged pallets on delivery'
FROM seed_event_src
WHERE status = 'DELIVERED' AND r_misc > 0.985;

-- ARRIVED_AT_DELIVERY and DELIVERED
INSERT INTO transport.load_event (load_id, event_type, occurred_at, city, state, latitude, longitude)
SELECT id, 'ARRIVED_AT_DELIVERY', delivered_at - make_interval(mins => 30 + (r_misc * 120)::int),
       destination_city, destination_state, d_lat, d_lon
FROM seed_event_src WHERE status = 'DELIVERED';

INSERT INTO transport.load_event (load_id, event_type, occurred_at, city, state, latitude, longitude)
SELECT id, 'DELIVERED', delivered_at, destination_city, destination_state, d_lat, d_lon
FROM seed_event_src WHERE status = 'DELIVERED';

-- ---------------------------------------------------------------------------
-- Tidy up and report
-- ---------------------------------------------------------------------------
DROP TABLE seed_event_src, seed_load, seed_load_plan, seed_lane_geo, seed_lane, seed_city;

ANALYZE transport.carrier;
ANALYZE transport.load;
ANALYZE transport.load_event;

SELECT 'carrier' AS table_name, count(*) FROM transport.carrier
UNION ALL SELECT 'load', count(*) FROM transport.load
UNION ALL SELECT 'load_event', count(*) FROM transport.load_event;
