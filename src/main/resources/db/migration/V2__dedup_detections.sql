-- удалить дубликаты, оставить самую свежую запись по (video_id, merge_ms)
with ranked as (
    select id,
           row_number() over (
               partition by video_id, merge_ms
               order by created_at desc, id desc
               ) as rn
    from detections
)
delete from detections d
    using ranked r
where d.id = r.id
  and r.rn > 1;
