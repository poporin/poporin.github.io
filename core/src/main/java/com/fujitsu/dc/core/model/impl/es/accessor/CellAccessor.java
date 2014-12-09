/**
 * personium.io
 * Copyright 2014 FUJITSU LIMITED
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fujitsu.dc.core.model.impl.es.accessor;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fujitsu.dc.common.ads.AdsWriteFailureLogInfo;
import com.fujitsu.dc.common.es.EsIndex;
import com.fujitsu.dc.common.es.query.DcQueryBuilder;
import com.fujitsu.dc.common.es.query.DcQueryBuilders;
import com.fujitsu.dc.common.es.response.DcSearchHit;
import com.fujitsu.dc.common.es.response.DcSearchResponse;
import com.fujitsu.dc.common.es.response.EsClientException;
import com.fujitsu.dc.core.DcCoreLog;
import com.fujitsu.dc.core.model.DavCmp;
import com.fujitsu.dc.core.model.impl.es.EsModel;
import com.fujitsu.dc.core.model.impl.es.QueryMapFactory;
import com.fujitsu.dc.core.model.impl.es.ads.AdsException;
import com.fujitsu.dc.core.model.impl.es.doc.EntitySetDocHandler;
import com.fujitsu.dc.core.model.lock.Lock;
import com.fujitsu.dc.core.model.lock.LockKeyComposer;

/**
 * Cellのアクセス処理を実装したクラス.
 */
public class CellAccessor extends AbstractEntitySetAccessor {

    /**
     * コンストラクタ.
     * @param index インデックス
     * @param name タイプ名
     * @param routingId routingId
     */
    public CellAccessor(EsIndex index, String name, String routingId) {
        super(index, name, routingId);
    }

    /**
     * セル配下のDavFile数を返却する.
     * @param cellId 削除対象のセルID
     * @param unitUserName ユニットユーザ名
     * @return セル配下のDavFile数
     */
    public long getDavFileTotalCount(String cellId, String unitUserName) {
        // CellAccessorはadインデックスに対するアクセスのため、ユニットユーザ側のアクセッサを取得
        DataSourceAccessor accessor = EsModel.dsa(unitUserName);

        // Countのみを取得するためサイズを0で指定
        Map<String, Object> countQuery = getDavFileFilterQuery(cellId);
        countQuery.put("size", 0);

        DcSearchResponse response = accessor.searchForIndex(cellId, countQuery);
        return response.getHits().getAllPages();
    }

    /**
     * セル配下のDavFileID一覧を返却する.
     * @param cellId 削除対象のセルID
     * @param unitUserName ユニットユーザ名
     * @param size 取得件数
     * @param from 取得開始位置
     * @return セル配下のDavFile数
     */
    public List<String> getDavFileIdList(String cellId, String unitUserName, int size, int from) {
        // CellAccessorはadインデックスに対するアクセスのため、ユニットユーザ側のアクセッサを取得
        DataSourceAccessor accessor = EsModel.dsa(unitUserName);

        Map<String, Object> searchQuery = getDavFileFilterQuery(cellId);
        searchQuery.put("size", size);
        searchQuery.put("from", from);

        DcSearchResponse response = accessor.searchForIndex(cellId, searchQuery);
        List<String> davFileIdList = new ArrayList<String>();
        for (DcSearchHit hit : response.getHits().getHits()) {
            davFileIdList.add(hit.getId());
        }
        return davFileIdList;
    }

    private Map<String, Object> getDavFileFilterQuery(String cellId) {
        Map<String, Object> cellQuery = new HashMap<String, Object>();
        cellQuery.put("c", cellId);
        Map<String, Object> cellTermQuery = new HashMap<String, Object>();
        cellTermQuery.put("term", cellQuery);

        Map<String, Object> davTypeQuery = new HashMap<String, Object>();
        davTypeQuery.put("t", DavCmp.TYPE_DAV_FILE);
        Map<String, Object> davTypeTermQuery = new HashMap<String, Object>();
        davTypeTermQuery.put("term", davTypeQuery);

        List<Map<String, Object>> andQueryList = new ArrayList<Map<String, Object>>();
        andQueryList.add(cellTermQuery);
        andQueryList.add(davTypeTermQuery);

        Map<String, Object> query = QueryMapFactory.filteredQuery(null, QueryMapFactory.mustQuery(andQueryList));

        Map<String, Object> countQuery = new HashMap<String, Object>();
        countQuery.put("query", query);
        return countQuery;
    }

    /**
     * セル配下のエンティティを一括削除する.
     * @param cellId 削除対象のセルID
     * @param unitUserName ユニットユーザ名
     */
    public void cellBulkDeletion(String cellId, String unitUserName) {
        DataSourceAccessor accessor = EsModel.dsa(unitUserName);

        // セルIDを指定してelasticsearchからセル関連エンティティを一括削除する
        DcQueryBuilder matchQuery = DcQueryBuilders.matchQuery("c", cellId);
        try {
            accessor.deleteByQuery(cellId, matchQuery);
            log.info("KVS Deletion Success.");
        } catch (EsClientException e) {
            // 削除に失敗した場合はログを出力して処理を続行する
            log.warn(String.format("Delete CellResource From KVS Failed. CellId:[%s], CellUnitUserName:[%s]",
                    cellId, unitUserName), e);
        }
    }

    /**
     * マスターデータを登録する.
     * @param docHandler 登録データ
     */
    @Override
    protected void createAds(EntitySetDocHandler docHandler) {
        // 登録に成功した場合、マスタデータを書き込む
        if (getAds() != null) {
            String unitUserName = docHandler.getUnitUserName();
            try {
                getAds().createCell(unitUserName, docHandler);
            } catch (AdsException e) {
                // Indexが存在しない場合はインデックスを作成する。
                if (e.getCause() instanceof SQLException
                        && MYSQL_BAD_TABLE_ERROR.equals(((SQLException) e.getCause()).getSQLState())) {
                    DcCoreLog.Server.ES_INDEX_NOT_EXIST.params(unitUserName).writeLog();
                    createAdsIndex(unitUserName);
                    try {
                        getAds().createCell(unitUserName, docHandler);
                    } catch (AdsException e1) {
                        DcCoreLog.Server.DATA_STORE_ENTITY_CREATE_FAIL.params(e1.getMessage()).reason(e1).writeLog();

                        // Adsの登録に失敗した場合は、専用のログに書込む
                        String lockKey = LockKeyComposer.fullKeyFromCategoryAndKey(Lock.CATEGORY_ODATA,
                                docHandler.getCellId(), null, null);
                        AdsWriteFailureLogInfo loginfo = new AdsWriteFailureLogInfo(
                                docHandler.getUnitUserName(), docHandler.getType(), lockKey,
                                docHandler.getCellId(), docHandler.getId(),
                                AdsWriteFailureLogInfo.OPERATION_KIND.CREATE, 1, docHandler.getUpdated());
                        recordAdsWriteFailureLog(loginfo);
                    }
                } else {
                    DcCoreLog.Server.DATA_STORE_ENTITY_CREATE_FAIL.params(e.getMessage()).reason(e).writeLog();

                    // Adsの登録に失敗した場合は、専用のログに書込む
                    String lockKey = LockKeyComposer.fullKeyFromCategoryAndKey(Lock.CATEGORY_ODATA,
                            docHandler.getCellId(), null, null);
                    AdsWriteFailureLogInfo loginfo = new AdsWriteFailureLogInfo(
                            docHandler.getUnitUserName(), docHandler.getType(), lockKey,
                            docHandler.getCellId(), docHandler.getId(),
                            AdsWriteFailureLogInfo.OPERATION_KIND.CREATE, 1, docHandler.getUpdated());
                    recordAdsWriteFailureLog(loginfo);
                }
            }
        }
    }

    /**
     * マスターデータを更新する.
     * @param docHandler 登録データ
     * @param version Elasticsearchに登録されたドキュメントのバージョン
     */
    @Override
    protected void updateAds(EntitySetDocHandler docHandler, long version) {
        // 更新に成功した場合、マスタデータを更新する
        if (getAds() != null) {
            try {
                getAds().updateCell(docHandler.getUnitUserName(), docHandler);
            } catch (AdsException e) {
                DcCoreLog.Server.DATA_STORE_ENTITY_UPDATE_FAIL.params(e.getMessage()).reason(e).writeLog();

                // Adsの登録に失敗した場合は、専用のログに書込む
                String lockKey = LockKeyComposer.fullKeyFromCategoryAndKey(Lock.CATEGORY_ODATA,
                        docHandler.getCellId(), null, null);
                AdsWriteFailureLogInfo loginfo = new AdsWriteFailureLogInfo(
                        docHandler.getUnitUserName(), docHandler.getType(), lockKey,
                        docHandler.getCellId(), docHandler.getId(),
                        AdsWriteFailureLogInfo.OPERATION_KIND.UPDATE, version, docHandler.getUpdated());
                recordAdsWriteFailureLog(loginfo);
            }
        }
    }

    /**
     * マスタデータを削除する.
     * @param docHandler 削除データ
     * @param version 削除したデータのバージョン
     */
    @Override
    protected void deleteAds(EntitySetDocHandler docHandler, long version) {
        String id = docHandler.getId();
        String unitUserName = docHandler.getUnitUserName();

        // 削除に成功した場合、マスタデータを削除する
        if (getAds() != null) {
            try {
                getAds().deleteCell(unitUserName, id);
            } catch (AdsException e) {
                DcCoreLog.Server.DATA_STORE_ENTITY_DELETE_FAIL.params(e.getMessage()).reason(e).writeLog();

                // Adsの登録に失敗した場合は、専用のログに書込む
                String lockKey = LockKeyComposer.fullKeyFromCategoryAndKey(Lock.CATEGORY_ODATA,
                        docHandler.getCellId(), null, null);
                AdsWriteFailureLogInfo loginfo = new AdsWriteFailureLogInfo(
                        docHandler.getUnitUserName(), docHandler.getType(), lockKey,
                        docHandler.getCellId(), docHandler.getId(),
                        AdsWriteFailureLogInfo.OPERATION_KIND.DELETE, version, docHandler.getUpdated());
                recordAdsWriteFailureLog(loginfo);
            }
        }
    }
}
