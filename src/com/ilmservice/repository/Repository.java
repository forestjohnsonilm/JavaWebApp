package com.ilmservice.repository;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.annotation.PreDestroy;
import javax.ejb.Stateless;
import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Default;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.ilmservice.repository.IDbManager.IDbTransaction;
import com.ilmservice.repository.IRepository.Void;


@Default
@Dependent
public class Repository<V> implements IRepository<V> {

	private IDbScope db;
	
	private boolean hasMutableIndex = false;
	private IRepositoryIndex<?, V> immutableIndex;
	private final Map<Short, IRepositoryIndex<?, V>> indexes;
	private ParseFunction<byte[], V> parserFunction;
	private Function<V, byte[]> serializerFunction;
	private boolean isConfiguringIndexes = false;
	
	private Repository() 
	{
		this.indexes = new HashMap<Short, IRepositoryIndex<?, V>>();
		System.out.println("protobuf repo goin up         ");
	}
	
	@Override 
	public void configure (
		IDbScope db,
		ParseFunction<byte[], V> parser,
		Function<V, byte[]> serializer,
		Void configureIndexes)  
	{
		this.db = db;
		this.parserFunction = parser;
		this.serializerFunction = serializer;
		
		isConfiguringIndexes = true;
		configureIndexes.apply();
		if(immutableIndex == null) {
			throw new RuntimeException("You must have at least one immutable index.");
		}
		isConfiguringIndexes = false;
		
		// this is where you would run migrations
	}
	
	@Override
	public <K> IRepositoryIndex<K, V> configureIndex(
			short index,
			boolean mutable,
			Function<K, V> defaultSupplier,
			Function<V, K> getKeyFromValue, 
			Function <K, byte[]> getKeyBytesFromKey) throws Exception 
	{
		if(!isConfiguringIndexes) {
			throw new Exception("You must only configure indexes inside the index configuration callback.");
		}
		IRepositoryIndex<K, V> newIndex = new ProtobufIndex<K, V>(
			index,
			mutable,
			parserFunction, 
			defaultSupplier, 
			getKeyFromValue, 
			getKeyBytesFromKey
		);
		
		if(!mutable && immutableIndex == null) {
			immutableIndex = newIndex;
		}
		hasMutableIndex = hasMutableIndex || mutable;
		
		indexes.put(index, newIndex);
		return newIndex;
	}
	
	@Override
	public V put(V value) throws  IOException {
		
		Optional<V> oldValue = hasMutableIndex ? immutableIndex.getByValue(value) : Optional.empty();

		indexes.forEach((k, index) -> {
			byte[] newKey = index.getKeyBytesFromValue(value);
			byte[] oldKey = oldValue.isPresent() ? index.getKeyBytesFromValue(oldValue.get()) : null;
			if(oldKey != null && ByteArrayComparator.compare(oldKey, newKey) != 0) {
				if(!index.mutable()) {
					System.err.println("Index was marked mutable, but the key has changed.");
				} else {
					db.index(k).delete(oldKey);
				}
			}
			db.index(k).put(newKey, serializerFunction.apply(value));
		});
		
		return value;
	}
	
	@Override
	public void delete (V value) throws IOException {
		V usedValue = hasMutableIndex ? immutableIndex.getByValue(value).get() : value;
		
		indexes.forEach((k, index) -> {
			db.index(k).delete(index.getKeyBytesFromValue(usedValue));
		});
	}
	
	@PreDestroy
	public void close() {
		System.out.println("protobuf repo goin down");
	}
	
	public class ProtobufIndex<K, V> implements IRepositoryIndex<K, V> {
		public ProtobufIndex (
				short id,
				boolean mutable,
				ParseFunction<byte[], V> parser, 
				Function<K, V> defaultSupplier,
				Function<V, K> getKeyFromValue, 
				Function <K, byte[]> getKeyBytesFromKey) 
		{
			this.mutable = mutable;
			this.id = id;
			this.defaultSupplier = defaultSupplier;
			this.parserFunction = parser;
			this.getKeyFromValueFunction = getKeyFromValue;
			this.getKeyBytesFromKeyFunction = getKeyBytesFromKey;
		}
		
		private final boolean mutable;
		private final short id;
		private final Function<K, V> defaultSupplier;
		private final ParseFunction<byte[], V> parserFunction;
		private final Function<V, K> getKeyFromValueFunction;
		private final Function <K, byte[]> getKeyBytesFromKeyFunction;
		
		@Override
		public short getId() {
			return id;
		}
		
		@Override
		public boolean mutable() {
			return mutable;
		}
		
		@Override
		public V parse(byte[] data) throws IOException {
			return parserFunction.apply(data);
		}
		
		@Override
		public V getDefault(K keyOrNull) {
			return defaultSupplier.apply(keyOrNull);
		}
		
		@Override
		public K getKeyFrom(V value) {
			return getKeyFromValueFunction.apply(value);
		}
		
		@Override
		public byte[] getKeyBytesFromKey(K key) {
			return getKeyBytesFromKeyFunction.apply(key);
		}
		
		@Override
		public byte[] getKeyBytesFromValue(V value) {
			return getKeyBytesFromKeyFunction.apply(getKeyFromValueFunction.apply(value));
		}
		
		@Override
		public IRepositoryQuery<K, V> query() {
			return new ProtobufQuery<K, V>(this);
		}
		
		@Override
		public Optional<V> getByValue (V value) throws IOException {
			return this.get(this.getKeyFrom(value));
		}

		@Override
		public Optional<V> get (K key) throws IOException {
			byte[] value = db.index(this.id).get(this.getKeyBytesFromKey(key));
			if(value != null) {
				V parsed = this.parse(value);
				if(parsed != null) {
					return Optional.of(parsed);
				}
			}
			return Optional.empty();
		}
	}
	
	public class ProtobufQuery<K, V> implements IRepositoryQuery<K, V> {

		private ProtobufIndex<K, V> index;
		private boolean descending;
		private byte[] fromBytes, toBytes = null;
		
		public ProtobufQuery(ProtobufIndex<K, V> index) {
			this.index = index;
		}
		
		@Override
		public IRepositoryQuery<K, V> descending() {
			if(!descending && fromBytes != null) {
				byte[] toTemp = toBytes;
				toBytes = fromBytes;
				fromBytes = toTemp;
			}
			this.descending = true;
			
			return this;
		}

		@Override
		public IRepositoryQuery<K, V> range(K from, K to) {

			byte[] fromBytes = from != null ? index.getKeyBytesFromKey(from) : null;
			byte[] toBytes = to != null ? index.getKeyBytesFromKey(to) : null;
			
			// sort the values if they are both non null
			if(fromBytes != null && toBytes != null) {
				if( (ByteArrayComparator.compare(fromBytes, toBytes) > 0) ^ descending ) {
					byte[] tempToBytes = toBytes;
					toBytes = fromBytes;
					fromBytes = tempToBytes;
				}
			}
			
			this.fromBytes = fromBytes;
			this.toBytes = toBytes;
			return this;
		}

		@Override
		public <R> R withStream(Function<Stream<V>, R> action) {
			return db.index(index.getId())
				.withStream(fromBytes, toBytes, descending, 
					(byteStream) -> action.apply(
							byteStream.map((data) -> {
								V result = null;
								try {
									result = index.parse(data);
								} catch (IOException ex) {
									ex.printStackTrace();
								}
								return result;
							}
						)
					)
				);
		}
	}
}
