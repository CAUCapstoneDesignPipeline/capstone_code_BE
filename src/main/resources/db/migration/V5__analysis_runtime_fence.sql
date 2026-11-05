-- 관측한 비활성화는 재시작·빠른 재활성화 뒤에도 기존 작업을 복원하지 않는다.
create table analysis_runtime_fence (
 singleton boolean primary key default true check(singleton),
 epoch bigint not null default 0 check(epoch>=0)
);
insert into analysis_runtime_fence(singleton,epoch) values(true,0);
alter table analysis_job add column runtime_epoch bigint not null default 0 check(runtime_epoch>=0);
