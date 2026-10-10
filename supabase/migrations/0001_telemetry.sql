-- WorldTV telemetry schema. Self-contained: drops any previous telemetry objects and rebuilds.
-- Run in the Supabase SQL editor (or `supabase db push`). Safe to run again any time.
--
-- Design: the app only INSERTs (PostgREST upserts are refused under RLS unless a SELECT policy
-- exists, which would let the shipped publishable key read rows). installations / sessions /
-- playlists are therefore snapshot tables; the views pick the latest row per device / playlist.
-- Reads happen with the service_role key (dashboard) only.

-- ---------------------------------------------------------------------------
-- Start fresh: drop views then tables.
-- ---------------------------------------------------------------------------
drop view  if exists public.v_devices           cascade;
drop view  if exists public.v_playlists         cascade;
drop view  if exists public.v_playback_failures cascade;
drop view  if exists public.v_last_settings     cascade;
drop table if exists public.crashes             cascade;
drop table if exists public.events              cascade;
drop table if exists public.settings_snapshots  cascade;
drop table if exists public.sessions            cascade;
drop table if exists public.playlists           cascade;
drop table if exists public.installations        cascade;

-- ---------------------------------------------------------------------------
-- installations: one snapshot row per app run.
-- ---------------------------------------------------------------------------
create table public.installations (
  id              bigint generated always as identity primary key,
  installation_id text not null,               -- stable SHA-256 of ANDROID_ID (survives reinstall)
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  label           text,                        -- human title for the device list
  app_version     text,
  version_code    bigint,
  build_type      text,
  install_source  text,
  first_install   timestamptz,
  last_update     timestamptz,
  manufacturer    text,
  brand           text,
  model           text,
  device          text,
  android_release text,
  sdk             int,
  security_patch  text,
  is_tv           boolean,
  locale          text,
  ui_mode         text,
  last_ip         inet,
  last_country    text,
  last_asn        text,
  connection      text,
  last_seen       timestamptz not null default now(),
  hardware        jsonb                        -- full hardware/OS/display/storage/GPU dump
);
create index installations_installation_idx on public.installations (installation_id, last_seen desc);
create index installations_last_seen_idx    on public.installations (last_seen desc);
create index installations_model_idx        on public.installations (manufacturer, model);

-- ---------------------------------------------------------------------------
-- playlists: one snapshot row per sync (credentials stored as-is).
-- ---------------------------------------------------------------------------
create table public.playlists (
  id               bigint generated always as identity primary key,
  installation_id  text not null,
  local_id         bigint not null,            -- the app's internal playlist id
  name             text,
  kind             text,                        -- xtream | m3u | demo
  url              text,
  username         text,
  password         text,
  epg_url          text,
  expires          timestamptz,
  max_connections  int,
  status           text,
  categories_live  int,
  categories_movie int,
  categories_series int,
  entries_live     int,
  entries_movie    int,
  entries_series   int,
  last_sync        timestamptz,
  received_at      timestamptz not null default now()
);
create index playlists_installation_idx on public.playlists (installation_id, received_at desc);

-- ---------------------------------------------------------------------------
-- sessions: one row per app run (start). Session end is logged as an event.
-- ---------------------------------------------------------------------------
create table public.sessions (
  id              bigint generated always as identity primary key,
  session_id      text not null,               -- client-generated (device clock, ms)
  installation_id text not null,
  started_at      timestamptz not null,
  ended_at        timestamptz,
  received_at     timestamptz not null default now(),
  launch_reason   text,                        -- icon | launcher | boot | update
  end_reason      text,
  cold_start_ms   int,
  app_version     text,
  data            jsonb
);
create index sessions_installation_idx on public.sessions (installation_id, started_at desc);
create index sessions_session_idx      on public.sessions (session_id);

-- ---------------------------------------------------------------------------
-- events: the append-only telemetry timeline.
-- ---------------------------------------------------------------------------
create table public.events (
  id              bigint generated always as identity primary key,
  installation_id text not null,
  session_id      text,
  ts              timestamptz not null,        -- device time of the event
  received_at     timestamptz not null default now(),
  kind            text not null,               -- lifecycle | playback | sync | settings | search | subtitle | favourite | progress | update | playlist | navigation
  action          text not null,
  content_type    text,                        -- live | movie | series
  item_id         text,
  item_name       text,
  category        text,
  duration_ms     bigint,
  position_ms     bigint,
  success         boolean,
  error_code      text,
  error_message   text,
  app_version     text,
  data            jsonb
);
create index events_installation_ts_idx on public.events (installation_id, ts desc, id desc);
create index events_kind_idx            on public.events (kind, ts desc);
create index events_item_idx            on public.events (item_name);
create index events_data_gin            on public.events using gin (data jsonb_path_ops);

-- ---------------------------------------------------------------------------
-- crashes: uncaught exceptions / ANRs.
-- ---------------------------------------------------------------------------
create table public.crashes (
  id              bigint generated always as identity primary key,
  installation_id text not null,
  session_id      text,
  ts              timestamptz not null,
  received_at     timestamptz not null default now(),
  fatal           boolean not null default true,
  thread          text,
  exception       text,
  message         text,
  stacktrace      text,
  app_version     text,
  data            jsonb
);
create index crashes_installation_idx on public.crashes (installation_id, ts desc);
create index crashes_exception_idx    on public.crashes (exception);

-- ---------------------------------------------------------------------------
-- settings_snapshots: full app settings at start / on change.
-- ---------------------------------------------------------------------------
create table public.settings_snapshots (
  id              bigint generated always as identity primary key,
  installation_id text not null,
  ts              timestamptz not null,
  received_at     timestamptz not null default now(),
  app_version     text,
  settings        jsonb
);
create index settings_snapshots_installation_idx on public.settings_snapshots (installation_id, ts desc);

-- ---------------------------------------------------------------------------
-- Capture the client IP from the PostgREST forwarded header (no app change).
-- ---------------------------------------------------------------------------
create or replace function public.worldtv_set_client_ip() returns trigger
language plpgsql as $$
begin
  begin
    new.last_ip := split_part(current_setting('request.headers', true)::json ->> 'x-forwarded-for', ',', 1)::inet;
  exception when others then
    null;
  end;
  new.updated_at := now();
  new.created_at := coalesce(new.created_at, now());
  new.last_seen  := coalesce(new.last_seen, now());
  return new;
end $$;

create trigger trg_installations_ip before insert on public.installations
  for each row execute function public.worldtv_set_client_ip();

-- ---------------------------------------------------------------------------
-- RLS: anon may INSERT only (never SELECT). Reports use the service_role key.
-- ---------------------------------------------------------------------------
alter table public.installations      enable row level security;
alter table public.playlists          enable row level security;
alter table public.sessions           enable row level security;
alter table public.events             enable row level security;
alter table public.crashes            enable row level security;
alter table public.settings_snapshots enable row level security;

grant usage on schema public to anon;
grant insert on public.installations      to anon;
grant insert on public.playlists          to anon;
grant insert on public.sessions           to anon;
grant insert on public.events             to anon;
grant insert on public.crashes            to anon;
grant insert on public.settings_snapshots to anon;
grant usage, select on all sequences in schema public to anon;

create policy worldtv_insert on public.installations      for insert to anon with check (true);
create policy worldtv_insert on public.playlists          for insert to anon with check (true);
create policy worldtv_insert on public.sessions           for insert to anon with check (true);
create policy worldtv_insert on public.events             for insert to anon with check (true);
create policy worldtv_insert on public.crashes            for insert to anon with check (true);
create policy worldtv_insert on public.settings_snapshots for insert to anon with check (true);

-- ---------------------------------------------------------------------------
-- Report views (read with the service_role key only).
-- ---------------------------------------------------------------------------
create view public.v_devices as
select distinct on (i.installation_id)
       i.installation_id,
       i.label,
       i.manufacturer,
       i.model,
       i.android_release,
       i.sdk,
       i.is_tv,
       i.ui_mode,
       i.app_version,
       i.last_ip,
       i.last_country,
       i.connection,
       i.first_install,
       i.last_seen,
       (select count(*) from public.sessions s where s.installation_id = i.installation_id) as session_count,
       (select count(*) from public.events e   where e.installation_id = i.installation_id) as event_count,
       (select count(*) from public.playlists p where p.installation_id = i.installation_id) as playlist_count
from public.installations i
order by i.installation_id, i.last_seen desc nulls last;

create view public.v_playlists as
select distinct on (installation_id, local_id) *
from public.playlists
order by installation_id, local_id, received_at desc;

create view public.v_playback_failures as
select e.installation_id,
       e.provider_host,
       count(*)                                   as attempts,
       count(*) filter (where e.success is false)  as failures,
       round(100.0 * count(*) filter (where e.success is false) / greatest(count(*), 1), 1) as failure_pct
from (select installation_id, ts, success, data ->> 'stream_host' as provider_host
      from public.events where kind = 'playback' and action = 'play') e
where e.ts > now() - interval '7 days'
group by e.installation_id, e.provider_host
order by failures desc;

create view public.v_last_settings as
select distinct on (installation_id)
       installation_id, ts, app_version, settings
from public.settings_snapshots
order by installation_id, ts desc;
