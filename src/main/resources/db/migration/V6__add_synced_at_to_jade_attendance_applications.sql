-- JadeHR 신청내역과 상태를 맞춘 시각. status 에 APPROVED / REJECTED / DELETED 추가 (varchar 라 스키마 변경 없음)
alter table jade_attendance_applications add column synced_at timestamp(6) null;
