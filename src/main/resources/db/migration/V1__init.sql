create table if not exists videos (
  id           serial primary key,
  path         text not null unique,
  fps          double precision not null,
  frames       bigint not null,
  created_at   timestamptz not null default now()
);

create table if not exists detections (
  id           serial primary key,
  video_id     integer not null references videos(id) on delete cascade,
  merge_ms     integer not null,
  events_count integer not null,
  created_at   timestamptz not null default now()
);

create table if not exists events (
  id             serial primary key,
  detection_id   integer not null references detections(id) on delete cascade,
  t_ms           bigint not null
);

create index if not exists idx_detections_video on detections(video_id);
create index if not exists idx_events_detection on events(detection_id);
