create table if not exists cameras (
    id      serial primary key,
    name    text not null,
    url     text not null,
    active  boolean not null default true,
    created_at timestamptz not null default now()
);
create unique index if not exists uq_cameras_name on cameras(lower(name));