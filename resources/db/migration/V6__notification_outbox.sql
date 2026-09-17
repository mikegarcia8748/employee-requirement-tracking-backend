-- V6 The notification outbox (ERT-440). PRD 8.9, and 14 Q12 answered 2026-09-16: an SMTP relay on
-- internal mail. V5 is taken by ERT-420's nullable pin_hash, which is why this is not V5.
--
-- Written in the style V1 establishes: unquoted lowercase identifiers, no IF NOT EXISTS, no
-- dialect-specific syntax. 'MigrationTest.migration portability' checks this file too.
--
-- ERT-1010 lands the transport and drains this table; nothing transmits until it does. The outbox is
-- not merely a staging post for that, which is why it is worth a table rather than an in-memory
-- queue: it gives 8.1's "retry action" something to retry, makes "was the invitation sent?"
-- answerable, and survives a restart.
--
-- THE INVITATION'S BODY IS NEVER STORED, AND body IS NULLABLE FOR THAT REASON ALONE.
--
-- A rendered invitation contains a live credential -- since 2026-09-16 the link IS the whole of
-- authentication -- and "purge the row after delivery" only shrinks the window. It does not remove
-- the credential from a backup, a replica, or a write-ahead log. The only reason to keep it would be
-- to resend the SAME credential, and nothing needs that: resend-link reissues. So the invitation
-- renders at send time from the token held in memory for the duration of CreateHireUseCase, and its
-- row carries recipient, kind, employee, subject, status, attempts and last error -- enough to
-- answer "was it sent?" and to drive 8.1's failure indicator, with nothing in it worth stealing.
-- The other six kinds carry no credential and store their bodies normally.
--
-- The consequence is deliberate and is recorded here rather than discovered later: A FAILED
-- INVITATION CANNOT BE RE-RENDERED FROM THIS TABLE. Retrying one means reissuing the link, which is
-- ERT-1030's resend-link. The retry path below is for the six kinds that keep their body.
--
-- recipient IS NULLABLE because two of the seven Notifier methods take no address:
-- sendPacketReadyForReview and notifyHrOfSuspension go to HR, whose address is ERT-1010's
-- configuration rather than this ticket's. A sentinel string here would be a lie in a column other
-- code reads; kind already names the audience, and null means "resolve it at send time".
--
-- employee_id is NOT nullable: every Notifier method takes an Employee. That is what lets 8.1's
-- delivery-failure indicator be DERIVED from the latest row for a hire (E4) rather than needing a
-- column on employees that something has to remember to update.

create table notification_outbox (id varchar(12) primary key, kind varchar(32) not null, recipient varchar(320) null, employee_id varchar(8) not null, subject varchar(256) not null, body text null, status varchar(32) not null, attempts int default 0 not null, last_error text null, queued_at timestamp not null, last_attempt_at timestamp null, sent_at timestamp null, constraint fk_notification_outbox_employee_id__id foreign key (employee_id) references employees(id) on delete restrict on update restrict);
create index notification_outbox_employee_id on notification_outbox (employee_id);
create index notification_outbox_status on notification_outbox (status);
