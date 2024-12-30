package it.witboost.dataplatformshaper.service;

public interface CommonService<T, K> {

    void delete(K key) throws ServiceError;

    T read(K key) throws ServiceError;

    boolean exists(K key) throws ServiceError;
}
