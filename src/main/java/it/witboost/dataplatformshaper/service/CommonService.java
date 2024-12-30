package it.witboost.dataplatformshaper.service;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

public interface CommonService<T, K> {

    default PlatformTransactionManager getTransactionManager() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    void delete(K key) throws ServiceError;

    T read(K key) throws ServiceError;

    boolean exists(K key) throws ServiceError;

    default TransactionStatus getTransactionStatus(String txName) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName(txName);
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        TransactionStatus status = getTransactionManager().getTransaction(def);
        return status;
    }
}
