package tacos.data.outbox;

public enum OutboxStatus {
  NEW,
  PUBLISHING,
  PUBLISHED,
  FAILED
}
