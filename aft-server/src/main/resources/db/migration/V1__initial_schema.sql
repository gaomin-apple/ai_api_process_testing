create table projects (
    id text primary key,
    name text not null,
    openapi_source text,
    created_at text not null,
    updated_at text not null
);

create table environments (
    id text primary key,
    project_id text not null,
    name text not null,
    payload text not null,
    foreign key (project_id) references projects(id)
);

create table endpoints (
    id text primary key,
    project_id text not null,
    method text not null,
    path text not null,
    active integer not null default 1,
    payload text not null,
    foreign key (project_id) references projects(id)
);
create index idx_endpoints_project on endpoints(project_id, active);

create table flows (
    id text primary key,
    project_id text not null,
    name text not null,
    updated_at text not null,
    payload text not null,
    foreign key (project_id) references projects(id)
);
create index idx_flows_project on flows(project_id, updated_at);

create table runs (
    id text primary key,
    flow_id text not null,
    environment_id text not null,
    status text not null,
    started_at text not null,
    payload text not null,
    foreign key (flow_id) references flows(id),
    foreign key (environment_id) references environments(id)
);
create index idx_runs_flow on runs(flow_id, started_at);
