/* Copyright (C) 2010 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.datastore.mapping.mongo.engine;

import com.mongodb.*;
import org.bson.types.ObjectId;
import org.springframework.datastore.document.DocumentStoreConnectionCallback;
import org.springframework.datastore.document.mongodb.MongoTemplate;
import org.springframework.datastore.mapping.engine.AssociationIndexer;
import org.springframework.datastore.mapping.engine.NativeEntryEntityPersister;
import org.springframework.datastore.mapping.engine.PropertyValueIndexer;
import org.springframework.datastore.mapping.model.MappingContext;
import org.springframework.datastore.mapping.model.PersistentEntity;
import org.springframework.datastore.mapping.model.PersistentProperty;
import org.springframework.datastore.mapping.model.types.Association;
import org.springframework.datastore.mapping.mongo.MongoDatastore;
import org.springframework.datastore.mapping.mongo.MongoSession;
import org.springframework.datastore.mapping.mongo.query.MongoQuery;
import org.springframework.datastore.mapping.query.Query;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * A {@link org.springframework.datastore.mapping.engine.EntityPersister} implementation for the Mongo document store
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public class MongoEntityPersister extends NativeEntryEntityPersister<DBObject, Object> {

    public static final String MONGO_ID_FIELD = "_id";
    private MongoTemplate mongoTemplate;
    private boolean hasNumericalIdentifier = false;

	
	public MongoEntityPersister(MappingContext mappingContext,
			PersistentEntity entity, MongoSession mongoSession) {
		super(mappingContext, entity, mongoSession);
		MongoDatastore datastore = (MongoDatastore) mongoSession.getDatastore();
		this.mongoTemplate = datastore.getMongoTemplate(entity);

        final PersistentProperty identity = entity.getIdentity();
        hasNumericalIdentifier = Long.class.isAssignableFrom(identity.getType());
	}

	public Query createQuery() {
        return new MongoQuery((MongoSession) getSession(), getPersistentEntity());
	}

    @Override
    public String getEntityFamily() {
        return mongoTemplate.getDefaultCollectionName();
    }

    @Override
	protected void deleteEntry(String family, final Object key) {
		mongoTemplate.execute(new DocumentStoreConnectionCallback<DB, Object>() {
			public Object doInConnection(DB con) throws Exception {
				DBCollection dbCollection = con.getCollection(mongoTemplate.getDefaultCollectionName());
				
				DBObject dbo = new BasicDBObject();
                if(hasNumericalIdentifier) {
                    dbo.put(MONGO_ID_FIELD, key);
                }
                else {
                    dbo.put(MONGO_ID_FIELD, new ObjectId(key.toString()));
                }
				dbCollection.remove(dbo);
				return null;
			}
		});
	}

    private AtomicInteger sessionId = new AtomicInteger(0);
	@Override
	protected Object generateIdentifier(final PersistentEntity persistentEntity,
			DBObject id) {

        // if the id is a Long then generate and identifier
        if(hasNumericalIdentifier)  {
            return mongoTemplate.execute(new DocumentStoreConnectionCallback<DB, Long>() {

                public Long doInConnection(DB db) throws Exception {
                    DBCollection idCollection = db.getCollection(mongoTemplate.getDefaultCollectionName());
                    DBCursor result = idCollection.find().sort(new BasicDBObject(MONGO_ID_FIELD, -1)).limit(1);

                    long nextId;
                    if(result.hasNext()) {
                       Long current = getMappingContext().getConversionService().convert(result.next().get(MONGO_ID_FIELD), Long.class);
                       nextId = current + sessionId.incrementAndGet();
                    }
                    else {
                        nextId = sessionId.incrementAndGet();
                    }
                    return nextId;
                }
            });

        }
        return null;
	}

	@Override
	public PropertyValueIndexer getPropertyIndexer(PersistentProperty property) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AssociationIndexer getAssociationIndexer(Association association) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected DBObject createNewEntry(String family) {
		return new BasicDBObject();
	}

	@Override
	protected Object getEntryValue(DBObject nativeEntry, String property) {
		return nativeEntry.get(property);
	}

	@Override
	protected void setEntryValue(DBObject nativeEntry, String key, Object value) {
		nativeEntry.put(key, value);
	}

	@Override
	protected DBObject retrieveEntry(PersistentEntity persistentEntity,
			String family, final Serializable key) {
		return (DBObject) mongoTemplate.execute(new DocumentStoreConnectionCallback<DB, Object>() {
			public Object doInConnection(DB con) throws Exception {
				DBCollection dbCollection = con.getCollection(mongoTemplate.getDefaultCollectionName());
				
				DBObject dbo = new BasicDBObject();
                if(hasNumericalIdentifier) {
                    dbo.put(MONGO_ID_FIELD, key );
                }
                else {
                    dbo.put(MONGO_ID_FIELD, new ObjectId(key.toString()) );
                }
				return dbCollection.findOne(dbo);
				
			}
		});
	}

	@Override
	protected Object storeEntry(final PersistentEntity persistentEntity,
			final Object storeId, final DBObject nativeEntry) {
		return mongoTemplate.execute(new DocumentStoreConnectionCallback<DB, Object>() {
			public Object doInConnection(DB con) throws Exception {
				DBCollection dbCollection = con.getCollection(mongoTemplate.getDefaultCollectionName());

                if(hasNumericalIdentifier) {
                    while (true) {
                        try {
                            nativeEntry.put(MONGO_ID_FIELD, storeId);
                            dbCollection.insert(nativeEntry);
                            break;
                        } catch (MongoException.DuplicateKey e) {
                            nativeEntry.put(MONGO_ID_FIELD, generateIdentifier(persistentEntity, nativeEntry));
                        }
                    }

                    return nativeEntry.get(MONGO_ID_FIELD);
                }
                else {
                    dbCollection.insert(nativeEntry);
                    ObjectId id = (ObjectId) nativeEntry.get(MONGO_ID_FIELD);
                    return id.toString();
                }
			}
		});
	}

	@Override
	protected void updateEntry(PersistentEntity persistentEntity, final Object key,
			final DBObject entry) {
		mongoTemplate.execute(new DocumentStoreConnectionCallback<DB, Object>() {
			public Object doInConnection(DB con) throws Exception {
				DBCollection dbCollection = con.getCollection(mongoTemplate.getDefaultCollectionName());
				DBObject dbo = new BasicDBObject();
                if(hasNumericalIdentifier) {
                    dbo.put(MONGO_ID_FIELD, key);
                }
                else {
                    dbo.put(MONGO_ID_FIELD, new ObjectId(key.toString()));
                }
				dbCollection.update(dbo, entry);
				return null;
			}
		});
	}

	@Override
	protected void deleteEntries(String family, List<Object> keys) {
		// TODO: Suboptimal. Fix me.
		for (Object key : keys) {
			deleteEntry(family, key);
		}
		
	}


}
