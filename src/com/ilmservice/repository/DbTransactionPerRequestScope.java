package com.ilmservice.repository;

import java.io.IOException;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;

import com.ilmservice.repository.IDbManager.IDbIndex;
import com.ilmservice.repository.IDbManager.IDbTransaction;

@RequestScoped
@TransactionPerRequest
public class DbTransactionPerRequestScope implements IDbScope {
	@Inject private IDbManager db;
	
	private IDbTransaction transaction;
	
	@PostConstruct 
	private void beginTransaction () {
		transaction = db.openTransaction();
		System.out.println("beginTransaction");
	}
	
	public IDbIndex index(short indexId) {
		return transaction.index(indexId);
	}
	
	@PreDestroy
	public void endTransaction() {
		System.out.println("endTransaction");
		try {
			// TODO need to figure out how to conditionally execute this 
			// depending on whether an exception was thrown or not
			//transaction.execute();
			transaction.close();
		} catch (Exception e) {
			System.out.println("transaction failed and was never closed ??");
			e.printStackTrace();
		}
	}
}
