alter table `document_file`
add column chunk_count int not null  default 0
after status