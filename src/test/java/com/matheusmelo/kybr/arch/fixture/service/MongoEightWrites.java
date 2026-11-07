package com.matheusmelo.kybr.arch.fixture.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.bulk.ClientNamespacedWriteModel;
import java.util.List;

/** Rule fixture, never run: writes that need a MongoDB 8.0 server. */
public class MongoEightWrites {

    void crossCollection(MongoClient client, List<ClientNamespacedWriteModel> models) {
        client.bulkWrite(models);
    }

    UpdateOptions sortedUpdate() {
        return new UpdateOptions().sort(Sorts.ascending("x"));
    }

    ReplaceOptions sortedReplace() {
        return new ReplaceOptions().sort(Sorts.ascending("x"));
    }
}
