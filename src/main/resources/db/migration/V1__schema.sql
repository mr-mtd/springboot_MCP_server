-- Transportation schema: carrier -> load -> load_event.
-- Run as transport_migrator so it owns the tables; the default privileges set up
-- for transport_mcp then make every table readable by the MCP server automatically.

CREATE TABLE transport.carrier (
  id              bigserial   PRIMARY KEY,
  name            text        NOT NULL,
  mc_number       text        NOT NULL UNIQUE,   -- FMCSA motor carrier number
  dot_number      text        NOT NULL UNIQUE,   -- US DOT number
  scac            char(4)     UNIQUE,            -- standard carrier alpha code
  contact_email   text,
  phone           text,
  home_state      char(2),
  safety_rating   text        NOT NULL DEFAULT 'UNRATED'
                  CHECK (safety_rating IN ('SATISFACTORY','CONDITIONAL','UNSATISFACTORY','UNRATED')),
  active          boolean     NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE transport.load (
  id                     bigserial     PRIMARY KEY,
  load_number            text          NOT NULL UNIQUE,                    -- e.g. LD-000123
  carrier_id             bigint        REFERENCES transport.carrier(id),   -- null until assigned
  status                 text          NOT NULL CHECK (status IN
                           ('TENDERED','ASSIGNED','IN_TRANSIT','DELIVERED','CANCELLED')),
  equipment_type         text          NOT NULL CHECK (equipment_type IN ('DRY_VAN','REEFER','FLATBED')),
  origin_city            text          NOT NULL,
  origin_state           char(2)       NOT NULL,
  destination_city       text          NOT NULL,
  destination_state      char(2)       NOT NULL,
  miles                  integer       CHECK (miles > 0),
  weight_lbs             integer       CHECK (weight_lbs BETWEEN 1 AND 48000),
  rate_usd               numeric(10,2) CHECK (rate_usd >= 0),
  pickup_scheduled_at    timestamptz   NOT NULL,
  delivery_scheduled_at  timestamptz   NOT NULL,
  picked_up_at           timestamptz,
  delivered_at           timestamptz,
  created_at             timestamptz   NOT NULL DEFAULT now(),
  CHECK (delivery_scheduled_at > pickup_scheduled_at),
  CHECK (status IN ('TENDERED','CANCELLED') OR carrier_id IS NOT NULL),
  CHECK (status <> 'DELIVERED' OR delivered_at IS NOT NULL)
);
CREATE INDEX load_carrier_id_idx        ON transport.load (carrier_id);
CREATE INDEX load_status_delivery_idx   ON transport.load (status, delivery_scheduled_at);

CREATE TABLE transport.load_event (
  id            bigserial    PRIMARY KEY,
  load_id       bigint       NOT NULL REFERENCES transport.load(id) ON DELETE CASCADE,
  event_type    text         NOT NULL CHECK (event_type IN
                  ('TENDERED','ASSIGNED','PICKED_UP','CHECK_CALL','DELAY_REPORTED',
                   'ARRIVED_AT_DELIVERY','DELIVERED','CANCELLED','EXCEPTION')),
  occurred_at   timestamptz  NOT NULL,
  city          text,
  state         char(2),
  latitude      numeric(9,6),
  longitude     numeric(9,6),
  delay_reason  text CHECK (delay_reason IN
                  ('WEATHER','MECHANICAL','TRAFFIC','SHIPPER_DELAY','HOURS_OF_SERVICE','DAMAGE')),
  notes         text
);
CREATE INDEX load_event_load_time_idx ON transport.load_event (load_id, occurred_at);
CREATE INDEX load_event_delay_idx     ON transport.load_event (event_type)
  WHERE event_type IN ('DELAY_REPORTED','EXCEPTION');
