package it.davidgreco.metacatalog.service;

public interface TaskFactory<T> {

  Task<T> createTask(T entity);
}
