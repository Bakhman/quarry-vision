-- события по детекции
create index if not exists idx_events_detection_id on events(detection_id);

-- детекции по видео
create index if not exists idx_detections_video_id on detections(video_id);

-- уникальность пары (video_id, merge_ms)
create unique index if not exists uq_detections_video_merge on detections(video_id, merge_ms);
