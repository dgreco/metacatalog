package it.witboost.dataplatformshaper.service;

public interface CommonService<T, K> {

    T read(K key) throws ServiceError;

    void delete(K key) throws ServiceError;

    boolean exists(K key) throws ServiceError;
}
