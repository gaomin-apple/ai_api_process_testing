create table folders (
    id text primary key,
    project_id text not null,
    name text not null,
    sort_order integer not null default 0,
    foreign key (project_id) references projects(id)
);
create index idx_folders_project on folders(project_id, sort_order);

alter table endpoints add column folder_id text;
