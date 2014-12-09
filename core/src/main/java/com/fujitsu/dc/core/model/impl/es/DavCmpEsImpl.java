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
package com.fujitsu.dc.core.model.impl.es;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.StreamingOutput;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.codec.CharEncoding;
import org.apache.http.HttpStatus;
import org.apache.wink.webdav.model.Creationdate;
import org.apache.wink.webdav.model.Getcontentlength;
import org.apache.wink.webdav.model.Getcontenttype;
import org.apache.wink.webdav.model.Getlastmodified;
import org.apache.wink.webdav.model.Multistatus;
import org.apache.wink.webdav.model.ObjectFactory;
import org.apache.wink.webdav.model.Prop;
import org.apache.wink.webdav.model.Propertyupdate;
import org.apache.wink.webdav.model.Propfind;
import org.apache.wink.webdav.model.Resourcetype;
import org.apache.wink.webdav.model.Response;
import org.apache.wink.webdav.model.WebDAVModelHelper;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.odata4j.producer.CountResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.fujitsu.dc.common.auth.token.Role;
import com.fujitsu.dc.common.es.response.DcActionResponse;
import com.fujitsu.dc.common.es.response.DcGetResponse;
import com.fujitsu.dc.common.es.response.DcIndexResponse;
import com.fujitsu.dc.common.es.response.DcSearchHit;
import com.fujitsu.dc.common.es.response.DcSearchHits;
import com.fujitsu.dc.common.es.response.DcSearchResponse;
import com.fujitsu.dc.common.es.util.DcUUID;
import com.fujitsu.dc.common.es.util.IndexNameEncoder;
import com.fujitsu.dc.common.utils.DcCoreUtils;
import com.fujitsu.dc.core.DcCoreConfig;
import com.fujitsu.dc.core.DcCoreException;
import com.fujitsu.dc.core.DcCoreLog;
import com.fujitsu.dc.core.auth.AccessContext;
import com.fujitsu.dc.core.auth.OAuth2Helper.Key;
import com.fujitsu.dc.core.http.header.ByteRangeSpec;
import com.fujitsu.dc.core.http.header.RangeHeaderHandler;
import com.fujitsu.dc.core.model.Box;
import com.fujitsu.dc.core.model.Cell;
import com.fujitsu.dc.core.model.DavCmp;
import com.fujitsu.dc.core.model.DavNode;
import com.fujitsu.dc.core.model.ModelFactory;
import com.fujitsu.dc.core.model.ctl.ComplexType;
import com.fujitsu.dc.core.model.ctl.EntityType;
import com.fujitsu.dc.core.model.file.BinaryDataAccessException;
import com.fujitsu.dc.core.model.file.BinaryDataAccessor;
import com.fujitsu.dc.core.model.file.BinaryDataNotFoundException;
import com.fujitsu.dc.core.model.file.StreamingOutputForDavFile;
import com.fujitsu.dc.core.model.file.StreamingOutputForDavFileWithRange;
import com.fujitsu.dc.core.model.impl.es.accessor.DavNodeAccessor;
import com.fujitsu.dc.core.model.impl.es.accessor.EntitySetAccessor;
import com.fujitsu.dc.core.model.impl.es.doc.EsDocHandler;
import com.fujitsu.dc.core.model.impl.es.odata.UserSchemaODataProducer;
import com.fujitsu.dc.core.model.jaxb.Acl;
import com.fujitsu.dc.core.model.jaxb.ObjectIo;
import com.fujitsu.dc.core.model.lock.Lock;
import com.fujitsu.dc.core.model.lock.LockManager;
import com.fujitsu.dc.core.odata.DcODataProducer;

/**
 * DavCmpのElastic Search実装.
 */
public class DavCmpEsImpl implements DavCmp, EsDocHandler {
    String nodeId;
    Box box;
    Cell cell;
    DavNode davNode;
    Long version;
    ObjectFactory of;
    String name;
    Acl acl;
    DavCmpEsImpl parent;
    String confidentialLevel;
    List<String> ownerRepresentativeAccounts = new ArrayList<String>();

    /**
     * Esの検索結果出力上限.
     */
    private static final int TOP_NUM = DcCoreConfig.getEsTopNum();

    /**
     * ログ.
     */
    static Logger log = LoggerFactory.getLogger(DavCmpEsImpl.class);

    DavCmpEsImpl() {
    }

    /**
     * Boxをロックする.
     * @return 自ノードのロック
     */
    public Lock lock() {
        return LockManager.getLock(Lock.CATEGORY_DAV, null, this.box.getId(), null);
    }

    /**
     * @return ETag文字列.
     */
    @Override
    public String getEtag() {
        StringBuilder sb = new StringBuilder("\"");
        sb.append(this.version);
        sb.append("-");
        sb.append(this.davNode.getUpdated());
        sb.append("\"");
        return sb.toString();
    }

    /**
     * コンストラクタ.
     * @param name 担当パスコンポーネント名
     * @param parent 親部品
     * @param cell Cell
     * @param box Box
     * @param nodeId ノードID
     */
    public DavCmpEsImpl(final String name,
            final DavCmpEsImpl parent,
            final Cell cell,
            final Box box,
            final String nodeId) {
        this.cell = cell;
        this.box = box;
        this.name = name;
        this.parent = parent;
        this.nodeId = nodeId;
        this.of = new ObjectFactory();
        if (this.nodeId != null) {
            this.load();
        }
    }

    @Override
    public boolean isEmpty() {

        String type = this.getType();
        if (DavCmp.TYPE_COL_WEBDAV.equals(type)) {
            return !(this.getChildrenCount() > 0);
        } else if (DavCmp.TYPE_COL_BOX.equals(type)) {
            return !(this.getChildrenCount() > 0);
        } else if (DavCmp.TYPE_COL_ODATA.equals(type)) {
            // Collectionに紐づくEntityTypeの一覧を取得する
            // EntityTypeに紐づくリソース(AsssociationEndなど)はEntityTypeが必ず親となる関係であるため
            // EntityTypeのみ検索すれば、EntityTypeに紐づくリソースまでチェックする必要はない
            UserSchemaODataProducer producer = new UserSchemaODataProducer(this.cell, this);
            CountResponse cr = producer.getEntitiesCount(EntityType.EDM_TYPE_NAME, null);
            if (cr.getCount() > 0) {
                return false;
            }
            // Collectionに紐づくComplexTypeの一覧を取得する
            // ComplexTypeに紐づくリソース(ComplexTypeProperty)はComplexTypeが必ず親となる関係であるため
            // ComplexTypeのみ検索すれば、ComplexTypeに紐づくリソースまでチェックする必要はない
            cr = producer.getEntitiesCount(ComplexType.EDM_TYPE_NAME, null);
            return cr.getCount() < 1;
        } else if (DavCmp.TYPE_COL_SVC.equals(type)) {
            return !(this.getChild(SERVICE_SRC_COLLECTION).getChildrenCount() > 0);
        }
        DcCoreLog.Misc.UNREACHABLE_CODE_ERROR.writeLog();
        throw DcCoreException.Server.UNKNOWN_ERROR;
    }

    @Override
    public void makeEmpty() {
        // TODO 実装
    }

    /**
     * ACLのgetter.
     * @return acl
     */
    public Acl getAcl() {
        return this.acl;
    }

    /**
     * スキーマ認証のレベルを返す.
     * @return スキーマ認証レベル
     */
    public String getConfidentialLevel() {
        return this.confidentialLevel;
    }

    /**
     * ユニット昇格許可ユーザ設定を返す.
     * @return ユニット昇格許可ユーザ設定
     */
    public List<String> getOwnerRepresentativeAccounts() {
        return this.ownerRepresentativeAccounts;
    }

    /**
     * Cellレベルかのチェックをする.
     * @return Cellレベルの場合はtrueを返却
     */
    public boolean isCellLevel() {
        if (this.box != null) {
            return false;
        }
        return true;
    }

    /**
     * 再読み込み.
     */
    @SuppressWarnings("unchecked")
    public final void load() {
        DcGetResponse res = getNode();
        if (res == null) {
            // Boxから辿ってidで検索して、Davデータに不整合があった場合
            throw DcCoreException.Dav.DAV_INCONSISTENCY_FOUND;
        }
        this.version = res.version();
        this.davNode = DavNode.createFromJsonString(res.getId(), res.sourceAsString());
        if (this.davNode != null) {
            // Map<String, Object> aclObj = (Map<String, Object>) json.get("acl");
            // TODO JSONParseを２回やっていて効率悪い. JSON Parseの方式は何かに一本化すべき.
            String jsonStr = res.sourceAsString();
            JSONParser parser = new JSONParser();
            try {
                JSONObject jo = (JSONObject) parser.parse(jsonStr);
                JSONObject aclObj = (JSONObject) jo.get(DavNode.KEY_ACL);
                if (aclObj != null) {
                    log.debug(aclObj.toJSONString());
                    // principalのhref の値を ロールID（__id）からロールリソースURLに変換する。
                    // base:xml値の設定
                    String baseUrlStr = createBaseUrlStr();
                    roleIdToName(aclObj.get(KEY_ACE), baseUrlStr);

                    // ConfidentialLevelの取り出し
                    this.confidentialLevel = (String) aclObj.get(KEY_REQUIRE_SCHEMA_AUTHZ);

                    this.acl = Acl.fromJson(aclObj.toJSONString());
                    this.acl.setBase(baseUrlStr);
                    log.debug(this.acl.toJSON());
                }

                Map<String, String> props = (Map<String, String>) jo.get(DavNode.KEY_PROPS);
                if (props != null) {
                    for (Map.Entry<String, String> entry : props.entrySet()) {
                        String key = entry.getKey();
                        String val = entry.getValue();
                        int idx = key.indexOf("@");
                        String elementName = key.substring(0, idx);
                        String namespace = key.substring(idx + 1);
                        QName keyQName = new QName(namespace, elementName);

                        Element element = parseProp(val);
                        String elementNameSpace = element.getNamespaceURI();
                        // ownerRepresentativeAccountsの取り出し
                        if (Key.PROP_KEY_OWNER_REPRESENTIVE_ACCOUNTS.equals(keyQName)) {
                            NodeList accountNodeList = element.getElementsByTagNameNS(elementNameSpace,
                                    Key.PROP_KEY_OWNER_REPRESENTIVE_ACCOUNT.getLocalPart());
                            for (int i = 0; i < accountNodeList.getLength(); i++) {
                                this.ownerRepresentativeAccounts.add(accountNodeList.item(i).getTextContent().trim());
                            }
                        }
                    }
                }
            } catch (ParseException e) {
                // ESのJSONが壊れている状態。
                throw DcCoreException.Dav.FS_INCONSISTENCY_FOUND.reason(e);
            }
        }
    }

    /**
     * Nodeのデータを取得する.
     * @return Node取得結果
     */
    public DcGetResponse getNode() {
        DcGetResponse res = this.getEsColType().get(this.nodeId);
        return res;
    }

    private Element parseProp(String value) {
        // valをDOMでElement化
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = null;
        Document doc = null;
        try {
            builder = factory.newDocumentBuilder();
            ByteArrayInputStream is = new ByteArrayInputStream(value.getBytes(CharEncoding.UTF_8));
            doc = builder.parse(is);
        } catch (Exception e1) {
            throw DcCoreException.Dav.DAV_INCONSISTENCY_FOUND.reason(e1);
        }
        Element e = doc.getDocumentElement();
        return e;
    }

    /**
     * バックエンド操作に用いるEsTypeオブジェクトを取得します.
     * @return EsTypeオブジェクト
     */
    public DavNodeAccessor getEsColType() {
        return this.parent.getEsColType();
    }

    @SuppressWarnings("unchecked")
    @Override
    public final Multistatus propfind(final Propfind propfind, final String depth, final String url,
            final boolean isAclRead) {
        String reqUri = url;

        // Depthヘッダの有効な値は 0, 1
        // infinityの場合はサポートしないので403で返す
        if ("infinity".equals(depth)) {
            throw DcCoreException.Dav.PROPFIND_FINITE_DEPTH;
        } else if (depth == null) {
            throw DcCoreException.Dav.INVALID_DEPTH_HEADER.params("null");
        } else if (!("0".equals(depth) || "1".equals(depth))) {
            throw DcCoreException.Dav.INVALID_DEPTH_HEADER.params(depth);
        }

        // reqUri = currentUrl.getPath();
        // 最後が/でおわるときは、それを取る
        if (reqUri.endsWith("/")) {
            reqUri = reqUri.substring(0, reqUri.length() - 1);
        }
        String[] paths = reqUri.split("/");
        String nm = "";
        if (paths.length > 0) {
            nm = paths[paths.length - 1];
        }

        Multistatus res = this.of.createMultistatus();

        // リソース名がマルチバイトの場合、URLエスケープを行う
        int resourcePos = reqUri.lastIndexOf("/");
        if (resourcePos != -1) {
            String resourceName = reqUri.substring(resourcePos + 1);
            try {
                resourceName = URLEncoder.encode(resourceName, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                log.debug("UnsupportedEncodingException");
            }
            String collectionUrl = reqUri.substring(0, resourcePos);
            reqUri = collectionUrl + "/" + resourceName;
        }

        List<org.apache.wink.webdav.model.Response> resps = res.getResponse();
        org.apache.wink.webdav.model.Response r0 = this.createDavResponse(nm, reqUri, this.davNode.getSource(),
                propfind, isAclRead);
        resps.add(r0);

        // Depth が0ならここで終わり。
        if ("0".equals(depth)) {
            return res;
        }

        DcSearchResponse resp = getChildResource();
        if (resp == null) {
            return res;
        }

        DcSearchHit[] hits = resp.getHits().getHits();

        if (hits.length == 0) {
            return res;
        }

        // 子要素をnodeIdをキーに格納
        final Map<String, Map<String, Object>> mapJson = new HashMap<String, Map<String, Object>>();
        for (DcSearchHit hit : hits) {
            String id = hit.getId();
            Map<String, Object> childJson = hit.getSource();
            mapJson.put(id, childJson);
        }

        // Responseを追加
        Iterator<String> itr = this.davNode.getChildren().keySet().iterator();
        while (itr.hasNext()) {
            String childName = itr.next();
            String childNodeId = this.davNode.getChildren().get(childName);
            Map<String, Object> childJson = mapJson.get(childNodeId);

            try {
                childName = URLEncoder.encode(childName, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                log.debug("UnsupportedEncodingException:" + childName);
            }
            org.apache.wink.webdav.model.Response rs = this.createDavResponse(childName, reqUri + "/" + childName,
                    childJson, propfind, isAclRead);
            resps.add(rs);

        }

        return res;
    }

    /**
     * 子リソースの情報を取得する.
     * @return 子リソースの検索結果
     */
    public DcSearchResponse getChildResource() {
        // 子リソースの情報を取得する。
        Map<String, Object> source = new HashMap<String, Object>();

        // 暗黙フィルタを指定して、検索対象を検索条件の先頭に設定する（絞りこみ）
        List<Map<String, Object>> implicitFilters =
                QueryMapFactory.getImplicitFilters(
                        this.cell.getId(), this.box.getId(), null, null, this.getEsColType().getType());
        implicitFilters.add(0, QueryMapFactory.termQuery(DavNode.KEY_PARENT, this.nodeId));
        Map<String, Object> query = QueryMapFactory.mustQuery(implicitFilters);
        Map<String, Object> filteredQuery = QueryMapFactory.filteredQuery(null, query);
        source.put("query", filteredQuery);
        // 検索結果件数設定
        // Davの子要素には上限値があるため、設定ファイルから読み込んだ値を設定しておく
        // TODO 上限値を元の値より小さい値に変更する場合、このクエリでは取得できない情報が出てしまうが、
        // 検索性能の問題もあるため、対応方針を決めて修正する必要がある
        source.put("size", TOP_NUM);

        DcSearchResponse resp = this.getEsColType().search(source);
        return resp;
    }

    /*
     * proppatch メソッドへの対応. 保存の方式 key = namespaceUri + "@" + localName Value = inner XML String
     */
    @Override
    @SuppressWarnings("unchecked")
    public Multistatus proppatch(final Propertyupdate propUpdate, final String url) {
        long now = new Date().getTime();
        String reqUri = url;
        Multistatus ms = this.of.createMultistatus();
        Response res = this.of.createResponse();
        res.getHref().add(reqUri);

        // ロック
        Lock lock = this.lock();
        // 更新処理
        try {
            this.load(); // ロック後の最新情報取得
            Map<String, Object> propsJson = (Map<String, Object>) this.davNode.getProperties();

            List<Prop> propsToSet = propUpdate.getPropsToSet();

            for (Prop prop : propsToSet) {
                if (null == prop) {
                    throw DcCoreException.Dav.XML_CONTENT_ERROR;
                }
                List<Element> lpe = prop.getAny();
                for (Element elem : lpe) {
                    res.setProperty(elem, HttpStatus.SC_OK);
                    String key = elem.getLocalName() + "@" + elem.getNamespaceURI();
                    String value = DcCoreUtils.nodeToString(elem);
                    log.debug("key: " + key);
                    log.debug("val: " + value);
                    propsJson.put(key, value);
                }
            }

            List<Prop> propsToRemove = propUpdate.getPropsToRemove();
            for (Prop prop : propsToRemove) {
                if (null == prop) {
                    throw DcCoreException.Dav.XML_CONTENT_ERROR;
                }
                List<Element> lpe = prop.getAny();
                for (Element elem : lpe) {

                    String key = elem.getLocalName() + "@" + elem.getNamespaceURI();
                    String v = (String) propsJson.get(key);
                    log.debug("Removing key: " + key);
                    if (v == null) {
                        res.setProperty(elem, HttpStatus.SC_NOT_FOUND);
                    } else {
                        propsJson.remove(key);
                        res.setProperty(elem, HttpStatus.SC_OK);
                    }
                }
            }
            // 変更日付を更新。
            this.davNode.setUpdated(now);
            // 変更後JSONを書き出し。
            setPropToJson(propsJson);
            DcIndexResponse resp = updateNodeWithVersion();
            // ETAG生成用にVersionを反映
            this.version = resp.version();
        } finally {
            // ロック開放
            lock.release();
        }
        ms.getResponse().add(res);
        return ms;
    }

    /**
     * jsonにProp情報を設定する.
     * @param propsJson Prop
     */
    protected void setPropToJson(Map<String, Object> propsJson) {
    }

    /**
     * バージョン指定でNodeの情報を更新する.
     * @return 更新結果.
     */
    public DcIndexResponse updateNodeWithVersion() {
        DcIndexResponse resp;
        resp = this.getEsColType().update(this.nodeId, this.davNode, this.version);
        return resp;
    }

    /**
     * バージョン指定でNodeファイルの情報を更新する.
     * @return 更新結果.
     */
    public DcIndexResponse updateNodeWithVersionForFile() {
        DcIndexResponse resp;
        resp = this.getEsColType().updateForFile(this.nodeId, this.davNode, this.version);
        return resp;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final ResponseBuilder acl(final Reader reader) {
        // リクエストが空でない場合、パースして適切な拡張を行う。
        Acl aclToSet = null;
        try {
            aclToSet = ObjectIo.unmarshal(reader, Acl.class);
        } catch (Exception e1) {
            throw DcCoreException.Dav.XML_CONTENT_ERROR.reason(e1);
        }
        if (!aclToSet.validateAcl(isCellLevel())) {
            throw DcCoreException.Dav.XML_VALIDATE_ERROR;
        }
        JSONParser parser = new JSONParser();
        JSONObject aclJson = null;
        try {
            aclJson = (JSONObject) parser.parse(aclToSet.toJSON());
        } catch (ParseException e) {
            throw DcCoreException.Dav.XML_ERROR.reason(e);
        }
        // ロック
        Lock lock = this.lock();
        try {
            // ACLのxml:baseの値を取得する
            Object objAclBase = aclJson.get(KEY_ACL_BASE);
            String aclBase = null;
            if (objAclBase != null) {
                aclBase = (String) objAclBase;
            }

            // principalのhref の値を ロール名（Name） を ロールID（__id） に変換する。
            Object jsonObj = aclJson.get(KEY_ACE);
            JSONArray array = new JSONArray();
            if (jsonObj instanceof JSONObject) {
                array.add(jsonObj);
            } else {
                array = (JSONArray) jsonObj;
            }
            if (array != null) {
                for (Object ace : (JSONArray) array) {
                    JSONObject aceJson = (JSONObject) ace;
                    JSONObject principal = (JSONObject) aceJson.get(KEY_ACL_PRINCIPAL);
                    if (principal.get(KEY_ACL_HREF) != null) {
                        principal.put(KEY_ACL_HREF, roleResourceUrlToId((String) principal.get(KEY_ACL_HREF), aclBase));
                    } else if (principal.get(KEY_ACL_ALL) != null) {
                        principal.put(KEY_ACL_ALL, null);
                    }

                }
            }
            // ESへxm:baseの値を登録しない
            aclJson.remove(KEY_ACL_BASE);
            setAclToJson(aclJson);
            // このノードの更新を保存
            DcIndexResponse resp = updateNode();
            this.version = resp.getVersion();
            this.acl = aclToSet;
            // レスポンス
            return javax.ws.rs.core.Response.status(HttpStatus.SC_OK).header(HttpHeaders.ETAG, this.getEtag());
        } finally {
            lock.release();
        }
    }

    /**
     * jsonにACL情報を設定する.
     * @param aclJson ACL
     */
    protected void setAclToJson(JSONObject aclJson) {
        this.davNode.setAcl(aclJson);
    }

    /**
     * Nodeの情報を更新する.
     * @return 更新結果.
     */
    public DcIndexResponse updateNode() {
        DcIndexResponse resp;
        resp = this.getEsColType().update(this.nodeId, this.davNode);
        return resp;
    }

    @Override
    public final ResponseBuilder putForCreate(final String contentType, final InputStream inputStream) {
        // ファイル追加処理。

        long now = new Date().getTime();
        // creating node Document
        DavNode fileNode = new DavNode(this.cell.getId(), this.box.getId(), DavCmp.TYPE_DAV_FILE);
        fileNode.setParentId(this.parent.nodeId);

        // ファイルの情報
        Map<String, Object> data = new HashMap<String, Object>();
        fileNode.setFile(data);
        data.put(KEY_CONTENT_TYPE, contentType);

        BufferedInputStream bufferedInput = new BufferedInputStream(inputStream);

        // ロック
        Lock lock = this.lock();
        try {
            // ここで改めて存在確認が必要。
            // 親をロードしなおして、自身へのパスがないことの確認
            this.parent.load();
            this.nodeId = this.parent.davNode.getChildren().get(this.name);
            if (this.nodeId != null) {
                this.load();
                // 更新処理にまわる。
                return this.doPutForUpdate(contentType, inputStream, null);
            }

            // 親コレクション内のコレクション・ファイル数のチェック
            checkChildResourceCount();

            String newId = DcUUID.randomUUID();
            try {
                BinaryDataAccessor accessor = getBinaryDataAccessor();
                long writtenBytes = accessor.create(bufferedInput, newId);
                data.put(KEY_CONTENT_LENGTH, writtenBytes);
            } catch (BinaryDataNotFoundException nex) {
                throw DcCoreException.Dav.RESOURCE_NOT_FOUND.reason(nex);
            } catch (BinaryDataAccessException ex) {
                throw DcCoreException.Dav.FS_INCONSISTENCY_FOUND.reason(ex);
            }

            // メタデータの保存処理.
            this.davNode = fileNode;
            DcActionResponse res = createNodeWithId(newId);
            if (res instanceof DcIndexResponse) {
                this.nodeId = ((DcIndexResponse) res).getId();
                this.version = ((DcIndexResponse) res).version();
            } else if (res instanceof DcGetResponse) {
                this.nodeId = ((DcGetResponse) res).getId();
                this.version = ((DcGetResponse) res).version();
            }

            // adding newNode to this nodeDocument;
            this.parent.linkChild(this.name, this.nodeId, now);
        } finally {
            // ★UNLOCK
            lock.release();
            log.debug("unlock1");
        }

        return javax.ws.rs.core.Response.ok().status(HttpStatus.SC_CREATED).header(HttpHeaders.ETAG, this.getEtag());
    }

    /**
     * Node情報を作成する.
     * @return 作成結果
     */
    public DcActionResponse createNode() {
        DcIndexResponse res = null;
        String id = DcUUID.randomUUID();
        res = this.getEsColType().create(id, this.davNode);
        return res;
    }

    /**
     * IDを指定してNode情報を作成する.
     * @param id ID
     * @return 作成結果
     */
    public DcActionResponse createNodeWithId(String id) {
        DcActionResponse res;
        res = this.getEsColType().createForFile(id, this.davNode);
        return res;
    }

    @Override
    public final ResponseBuilder putForUpdate(final String contentType, final InputStream inputStream, String etag) {
        // ロック
        Lock lock = this.lock();
        try {
            return this.doPutForUpdate(contentType, inputStream, etag);
        } finally {
            // ロックを開放する
            lock.release();
            log.debug("unlock2");
        }
    }

    final ResponseBuilder doPutForUpdate(final String contentType, final InputStream inputStream, String etag) {
        // 現在時刻を取得
        long now = new Date().getTime();
        // 最新ノード情報をロード
        // TODO 全体として２回ロードしてしまうので、遅延ロードの仕組みを検討
        this.load();

        // 指定etagがあり、かつそれが*ではなく内部データから導出されるものと異なるときはエラー
        if (etag != null && !"*".equals(etag) && !this.getEtag().equals(etag)) {
            throw DcCoreException.Dav.ETAG_NOT_MATCH;
        }

        // 内容の更新をする
        this.davNode.setUpdated(now);
        Map<String, Object> data = new HashMap<String, Object>();

        data.put(KEY_CONTENT_TYPE, contentType);

        BufferedInputStream bufferedInput = new BufferedInputStream(inputStream);

        try {
            BinaryDataAccessor accessor = getBinaryDataAccessor();
            long writtenBytes = accessor.update(bufferedInput, this.nodeId);
            data.put(KEY_CONTENT_LENGTH, writtenBytes);
        } catch (BinaryDataNotFoundException nex) {
            throw DcCoreException.Dav.RESOURCE_NOT_FOUND.reason(nex);
        } catch (BinaryDataAccessException ex) {
            throw DcCoreException.Dav.FS_INCONSISTENCY_FOUND.reason(ex);
        }

        this.davNode.setFile(data);
        // 更新内容を書きだす
        DcIndexResponse res = updateNodeWithVersionForFile();
        this.version = res.getVersion();
        return javax.ws.rs.core.Response.ok().status(HttpStatus.SC_NO_CONTENT).header(HttpHeaders.ETAG, this.getEtag());

    }

    @Override
    public final ResponseBuilder get(final String ifNoneMatch, final String rangeHeaderField) {
        String storedEtag = this.getEtag();
        // ifNoneMatchヘッダの内容がマッチしたら Not-Modifiedを返す.
        if (storedEtag.equals(ifNoneMatch)) {
            return javax.ws.rs.core.Response.notModified().header(HttpHeaders.ETAG, storedEtag);
        }

        Map<String, Object> data = (Map<String, Object>) this.davNode.getFile();
        String contentType = (String) data.get(KEY_CONTENT_TYPE);

        BinaryDataAccessor accessor = getBinaryDataAccessor();
        ResponseBuilder res = null;
        final long fileSize = accessor.getSize(this.nodeId);

        // Rangeヘッダ解析処理
        final RangeHeaderHandler range = RangeHeaderHandler.parse(rangeHeaderField, fileSize);

        try {
            String fileFullPath = accessor.getFilePath(this.nodeId);

            // Rangeヘッダ指定の時とで処理の切り分け
            if (!range.isValid()) {
                // ファイル全体返却
                StreamingOutput sout = new StreamingOutputForDavFile(fileFullPath);
                res = davFileResponse(sout, fileSize, contentType);
            } else {
                // Range対応部分レスポンス

                // Rangeヘッダの範囲チェック
                if (!range.isSatisfiable()) {
                    DcCoreLog.Dav.REQUESTED_RANGE_NOT_SATISFIABLE.params(range.getRangeHeaderField()).writeLog();
                    throw DcCoreException.Dav.REQUESTED_RANGE_NOT_SATISFIABLE;
                }

                if (range.getByteRangeSpecCount() > 1) {
                    // MultiPartレスポンスには未対応
                    throw DcCoreException.Misc.NOT_IMPLEMENTED.params("Range-MultiPart");
                } else {
                    StreamingOutput sout = new StreamingOutputForDavFileWithRange(fileFullPath, fileSize, range);
                    res = davFileResponseForRange(sout, fileSize, contentType, range);
                }
            }
            return res.header(HttpHeaders.ETAG, this.getEtag())
                    .header(DcCoreUtils.HttpHeaders.ACCEPT_RANGES, RangeHeaderHandler.BYTES_UNIT);

        } catch (BinaryDataNotFoundException nex) {
            throw DcCoreException.Dav.DAV_UNAVAILABLE.reason(nex);
        }
    }

    /**
     * ファイルレスポンス処理.
     * @param sout StreamingOuputオブジェクト
     * @param fileSize ファイルサイズ
     * @param contentType コンテントタイプ
     * @return レスポンス
     */
    public ResponseBuilder davFileResponse(final StreamingOutput sout, long fileSize, String contentType) {
        return javax.ws.rs.core.Response.ok(sout).header(HttpHeaders.CONTENT_LENGTH, fileSize)
                .header(HttpHeaders.CONTENT_TYPE, contentType);
    }

    /**
     * ファイルレスポンス処理.
     * @param sout StreamingOuputオブジェクト
     * @param fileSize ファイルサイズ
     * @param contentType コンテントタイプ
     * @param range RangeHeaderHandler
     * @return レスポンス
     */
    public ResponseBuilder davFileResponseForRange(
            final StreamingOutput sout,
            long fileSize, String contentType, final RangeHeaderHandler range) {
        // MultiPartには対応しないため1個目のbyte-renge-setだけ処理する。
        int rangeIndex = 0;
        List<ByteRangeSpec> brss = range.getByteRangeSpecList();
        final ByteRangeSpec brs = brss.get(rangeIndex);

        // iPadのsafariにおいてChunkedのRangeレスポンスを処理できなかったので明にContent-Lengthを返却している。
        return javax.ws.rs.core.Response.status(HttpStatus.SC_PARTIAL_CONTENT).entity(sout)
                .header(DcCoreUtils.HttpHeaders.CONTENT_RANGE, brs.makeContentRangeHeaderField())
                .header(HttpHeaders.CONTENT_LENGTH, brs.getContentLength())
                .header(HttpHeaders.CONTENT_TYPE, contentType);
    }

    @Override
    public final String getName() {
        return this.name;
    }

    @Override
    public final DavCmp getChild(final String childName) {
        if (this.davNode == null || this.davNode.getChildren() == null) {
            return new DavCmpEsImpl(childName, this, this.cell, this.box, null);
        }
        String childNodeId = this.davNode.getChildren().get(childName);
        if (childNodeId == null) {
            return new DavCmpEsImpl(childName, this, this.cell, this.box, null);
        }
        return new DavCmpEsImpl(childName, this, this.cell, this.box, childNodeId);
    }

    @Override
    public final String getType() {
        if (this.davNode == null) {
            return DavCmp.TYPE_NULL;
        }
        return (String) this.davNode.getNodeType();
    }

    @SuppressWarnings({"unchecked" })
    final org.apache.wink.webdav.model.Response createDavResponse(final String pathName,
            final String href,
            final Map<String, Object> nodeJson,
            final Propfind propfind,
            final boolean isAclRead) {
        org.apache.wink.webdav.model.Response ret = this.of.createResponse();
        ret.getHref().add(href);

        // TODO v1.1 PROPFINDの内容によって返すものを変える
        if (propfind != null) {

            log.debug("isAllProp:" + propfind.isAllprop());
            log.debug("isPropName:" + propfind.isPropname());
        } else {
            log.debug("propfind is null");
        }

        /*
         * Displayname dn = of.createDisplayname(); dn.setValue(name); ret.setPropertyOk(dn);
         */

        Long updated = (Long) nodeJson.get(DavNode.KEY_UPDATED);
        if (updated != null) {
            Getlastmodified lm = of.createGetlastmodified();
            lm.setValue(new Date(updated));
            ret.setPropertyOk(lm);
        }
        Long published = (Long) nodeJson.get(DavNode.KEY_PUBLISHED);
        if (published != null) {
            Creationdate cd = of.createCreationdate();
            cd.setValue(new Date(published));
            ret.setPropertyOk(cd);
        }
        if (DavCmp.TYPE_DAV_FILE.equals(nodeJson.get(DavNode.KEY_NODE_TYPE))) {
            // Dav リソースとしての処理
            Resourcetype rt1 = of.createResourcetype();
            ret.setPropertyOk(rt1);
            // JSONObject atmts = (JSONObject) nodeJson.get("_attachments");
            Map<String, Object> data = (Map<String, Object>) nodeJson.get(DavNode.KEY_FILE);
            // JSONObject atmt = (JSONObject) atmts.get("attachment");
            // Long contentlength = (Long) atmt.get("length");
            Getcontentlength gcl = new Getcontentlength();
            gcl.setValue(String.valueOf(data.get(KEY_CONTENT_LENGTH)));
            ret.setPropertyOk(gcl);
            String contentType = (String) data.get(KEY_CONTENT_TYPE);
            Getcontenttype gct = new Getcontenttype();
            gct.setValue(contentType);
            ret.setPropertyOk(gct);
        } else if (DavCmp.TYPE_COL_ODATA.equals(nodeJson.get(DavNode.KEY_NODE_TYPE))) {
            // OData リソースとしての処理
            Resourcetype colRt = of.createResourcetype();
            colRt.setCollection(of.createCollection());
            List<Element> listElement = colRt.getAny();
            QName qname = new QName(DcCoreUtils.XmlConst.NS_DC1, DcCoreUtils.XmlConst.ODATA,
                    DcCoreUtils.XmlConst.NS_PREFIX_DC1);
            Element element = WebDAVModelHelper.createElement(qname);
            listElement.add(element);
            ret.setPropertyOk(colRt);

        } else if (DavCmp.TYPE_COL_SVC.equals(nodeJson.get(DavNode.KEY_NODE_TYPE))) {
            // Service リソースとしての処理
            Resourcetype colRt = of.createResourcetype();
            colRt.setCollection(of.createCollection());
            List<Element> listElement = colRt.getAny();
            QName qname = new QName(DcCoreUtils.XmlConst.NS_DC1, DcCoreUtils.XmlConst.SERVICE,
                    DcCoreUtils.XmlConst.NS_PREFIX_DC1);
            Element element = WebDAVModelHelper.createElement(qname);
            listElement.add(element);
            ret.setPropertyOk(colRt);

        } else {
            // Col リソースとしての処理
            Resourcetype colRt = of.createResourcetype();
            colRt.setCollection(of.createCollection());
            ret.setPropertyOk(colRt);

        }

        // ACLの処理
        if (isAclRead) {
            Map<String, Object> aclJson = (Map<String, Object>) nodeJson.get(DavNode.KEY_ACL);
            if (aclJson != null) {
                JSONParser parser = new JSONParser();
                JSONObject aclObj = null;
                try {
                    aclObj = (JSONObject) parser.parse(JSONObject.toJSONString(aclJson));
                } catch (ParseException e2) {
                    throw new WebApplicationException(e2);
                }
                Document aclDoc = null;

                // base:xml値の設定
                String baseUrlStr = createBaseUrlStr();
                // principalのhref の値を ロールID（__id）からロールリソースURLに変換する。
                this.roleIdToName(aclObj.get(KEY_ACE), baseUrlStr);

                final Acl objAcl = Acl.fromJson(aclObj.toJSONString());
                objAcl.setBase(baseUrlStr);
                objAcl.setRequireSchemaAuthz((String) aclObj.get(KEY_REQUIRE_SCHEMA_AUTHZ));
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                try {
                    aclDoc = dbf.newDocumentBuilder().newDocument();
                    ObjectIo.marshal(objAcl, aclDoc);
                } catch (Exception e) {
                    throw new WebApplicationException(e);
                }
                if (aclDoc != null) {
                    Element e = aclDoc.getDocumentElement();
                    ret.setPropertyOk(e);
                }
            }
        }
        Map<String, String> props = (Map<String, String>) nodeJson.get(DavNode.KEY_PROPS);
        if (props != null) {
            List<String> nsList = new ArrayList<String>();
            for (Map.Entry<String, String> entry : props.entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue();
                int idx = key.indexOf("@");
                String ns = key.substring(idx + 1, key.length());

                int nsIdx = nsList.indexOf(ns);
                if (nsIdx == -1) {
                    nsList.add(ns);
                }

                Element e = parseProp(val);

                ret.setPropertyOk(e);
            }

        }
        return ret;
    }

    @Override
    public final ResponseBuilder mkcol(final String type) {
        // 新しいノードを作成
        this.davNode = new DavNode(this.cell.getId(), this.box.getId(), type);
        this.davNode.setParentId(this.parent.nodeId);

        // ロック
        Lock lock = this.lock();
        try {
            // ここで改めて存在確認が必要。
            // 親をロードしなおして、自身へのパスがないことの確認
            this.parent.load();
            if (this.parent.davNode.getChildren().get(this.name) != null) {
                // クリティカルなタイミングで先にコレクションを作られてしまい、
                // すでに存在するのでEXCEPTION
                throw DcCoreException.Dav.METHOD_NOT_ALLOWED;
            }

            // コレクションの階層数のチェック
            DavCmpEsImpl current = this;
            int depth = 0;
            int maxDepth = DcCoreConfig.getMaxCollectionDepth();
            while (null != current.parent) {
                current = current.parent;
                depth++;
            }
            if (depth > maxDepth) {
                // コレクション数の制限を超えたため、400エラーとする
                throw DcCoreException.Dav.COLLECTION_DEPTH_ERROR;
            }

            // 親コレクション内のコレクション・ファイル数のチェック
            checkChildResourceCount();

            // 新しいノードを保存
            DcActionResponse resp = createNode();
            if (resp instanceof DcIndexResponse) {
                this.nodeId = ((DcIndexResponse) resp).getId();
                this.version = ((DcIndexResponse) resp).version();
            } else if (resp instanceof DcGetResponse) {
                this.nodeId = ((DcGetResponse) resp).getId();
                this.version = ((DcGetResponse) resp).version();
            }

            // 親ノードにポインタを追加
            this.parent.linkChild(this.name, this.nodeId, this.davNode.getPublished());
        } finally {
            // ★UNLOCK
            lock.release();
            log.debug("unlock");
        }

        // レスポンス
        return javax.ws.rs.core.Response.status(HttpStatus.SC_CREATED).header(HttpHeaders.ETAG, this.getEtag());
    }

    private void checkChildResourceCount() {
        // 親コレクション内のコレクション・ファイル数のチェック
        int maxChildResource = DcCoreConfig.getMaxChildResourceCount();
        if (this.parent.getChildrenCount() >= maxChildResource) {
            // コレクション内に作成可能なコレクション・ファイル数の制限を超えたため、400エラーとする
            throw DcCoreException.Dav.COLLECTION_CHILDRESOURCE_ERROR;
        }
    }

    @Override
    public final ResponseBuilder linkChild(final String childName, final String childNodeId, final Long asof) {
        this.davNode.getChildren().put(childName, childNodeId);
        this.davNode.setUpdated(asof);
        // このノードの更新を保存
        updateNode();
        return null;
    }

    @Override
    public final ResponseBuilder unlinkChild(final String childName, final Long asof) {
        this.davNode.getChildren().remove(childName);
        this.davNode.setUpdated(asof);

        // このノードの更新を保存
        updateNode();
        return null;
    }

    /**
     * リソースを削除する.
     * @param ifMatch ifMatchヘッダ
     * @return JaxRS応答オブジェクトビルダ
     */
    @Override
    public final ResponseBuilder delete(final String ifMatch) {
        // 指定etagがあり、かつそれが*ではなく内部データから導出されるものと異なるときはエラー
        if (ifMatch != null && !"*".equals(ifMatch) && !this.getEtag().equals(ifMatch)) {
            throw DcCoreException.Dav.ETAG_NOT_MATCH;
        }
        long now = new Date().getTime();
        // ロック
        Lock lock = this.lock();
        try {
            // WebDAVコレクションであって子孫リソースがあったら、エラーとする
            if (TYPE_COL_WEBDAV.equals(this.getType()) && this.davNode.getChildren().size() > 0) {
                throw DcCoreException.Dav.HAS_CHILDREN;
            }

            if (this.parent != null) {
                // ここで改めて存在確認が必要。
                // 親をロードしなおして、自身へのパスがないことの確認
                this.parent.load();
                if (this.parent.davNode.getChildren().get(this.name) == null) {
                    // クリティカルなタイミングで先に削除がかかってしまい、
                    // すでに存在しない場合はEXCEPTION
                    throw DcCoreException.Dav.RESOURCE_NOT_FOUND;
                }
                // Serviceコレクションのときは__srcの削除を行う
                // → 取得した子供が DavCmp ではない場合はデータ不整合として500エラーを返す（ありえない）
                if (TYPE_COL_SVC.equals(this.getType())) {
                    DavCmp srcCmp = this.getChild(DavCmp.SERVICE_SRC_COLLECTION);
                    if (srcCmp instanceof DavCmpEsImpl) {
                        ((DavCmpEsImpl) srcCmp).deleteNode();
                    } else {
                        throw DcCoreException.Dav.DAV_INCONSISTENCY_FOUND;
                    }
                }
                this.parent.unlinkChild(this.name, now);
            }
            deleteNode();
        } finally {
            // ★LOCK
            log.debug("unlcok");
            lock.release();
        }
        return javax.ws.rs.core.Response.ok().status(HttpStatus.SC_NO_CONTENT);
    }

    /**
     * Nodeを削除する.
     */
    public void deleteNode() {
        deleteNode(this.nodeId);
    }

    /**
     * Nodeid指定でNodeを削除する.
     * @param deleteNodeId 削除対象NodeId
     */
    public void deleteNode(final String deleteNodeId) {
        this.getEsColType().delete(this.davNode);

        BinaryDataAccessor accessor = getBinaryDataAccessor();
        try {
            accessor.delete(deleteNodeId);
        } catch (BinaryDataAccessException e) {
            throw DcCoreException.Dav.FS_INCONSISTENCY_FOUND.reason(e);
        }
    }

    /**
     * バイナリデータのアクセサのインスタンスを生成して返す.
     * @return アクセサのインスタンス
     */
    protected BinaryDataAccessor getBinaryDataAccessor() {
        String owner = cell.getOwner();
        String unitUserName = null;
        if (owner == null) {
            unitUserName = AccessContext.TYPE_ANONYMOUS;
        } else {
            unitUserName = IndexNameEncoder.encodeEsIndexName(owner);
        }

        return new BinaryDataAccessor(DcCoreConfig.getBlobStoreRoot(), unitUserName,
                DcCoreConfig.getPhysicalDeleteMode());
    }

    @Override
    public final DavCmp getParent() {
        return this.parent;
    }

    /**
     * BoxIdを取得する.
     * @return boxId
     */
    public final String getBoxId() {
        return this.box.getId();
    }

    /**
     * nodeIdを取得する.
     * @return nodeId
     */
    public final String getNodeId() {
        return this.nodeId;
    }

    @Override
    public final DcODataProducer getODataProducer() {
        return ModelFactory.ODataCtl.userData(this.cell, this);
    }

    @Override
    public final DcODataProducer getSchemaODataProducer(Cell cellObject) {
        return ModelFactory.ODataCtl.userSchema(cellObject, this);
    }

    @Override
    public final int getChildrenCount() {
        return this.davNode.getChildren().keySet().size();
    }

    private String roleResourceUrlToId(String roleUrl, String baseUrl) {
        EntitySetAccessor roleType = EsModel.cellCtl(this.cell, Role.EDM_TYPE_NAME);

        // roleNameがURLの対応
        URL url = null;
        try {
            // xml:baseの対応
            if (baseUrl != null && !"".equals(baseUrl)) {
                // URLの相対パス対応
                url = new URL(new URL(baseUrl), roleUrl);
            } else {
                url = new URL(roleUrl);
            }
        } catch (MalformedURLException e) {
            throw DcCoreException.Dav.ROLE_NOT_FOUND.reason(e);
        }

        Role role = null;
        try {
            role = new Role(url);
        } catch (MalformedURLException e) {
            log.info("Role URL:" + url.toString());
            throw DcCoreException.Dav.ROLE_NOT_FOUND;
        }

        // ロールリソースのセルURL部分はACL設定対象のセルURLと異なるものを指定することは許さない
        if (!(this.cell.getUrl().equals(role.getBaseUrl()))) {
            DcCoreLog.Dav.ROLE_NOT_FOUND.params("Cell different").writeLog();
            throw DcCoreException.Dav.ROLE_NOT_FOUND;
        }
        // Roleの検索
        List<Map<String, Object>> queries = new ArrayList<Map<String, Object>>();
        queries.add(QueryMapFactory.termQuery("c", this.cell.getId()));
        queries.add(QueryMapFactory.termQuery("s." + KEY_NAME + ".untouched", role.getName()));

        Map<String, Object> query = QueryMapFactory.filteredQuery(null, QueryMapFactory.mustQuery(queries));

        List<Map<String, Object>> filters = new ArrayList<Map<String, Object>>();
        if (!(Box.DEFAULT_BOX_NAME.equals(role.getBoxName()))) {
            // Roleがボックスと紐付く場合に、検索クエリを追加
            Box targetBox = this.cell.getBoxForName(role.getBoxName());
            if (targetBox == null) {
                throw DcCoreException.Dav.BOX_LINKED_BY_ROLE_NOT_FOUND.params(baseUrl);
            }
            String boxId = targetBox.getId();
            filters.add(QueryMapFactory.termQuery(KEY_LINK + "." + Box.EDM_TYPE_NAME, boxId));
        } else {
            // Roleがボックスと紐付かない場合にもnull検索クエリを追加
            filters.add(QueryMapFactory.missingFilter(KEY_LINK + "." + Box.EDM_TYPE_NAME));
        }

        Map<String, Object> source = new HashMap<String, Object>();
        if (!filters.isEmpty()) {
            source.put("filter", QueryMapFactory.andFilter(filters));
        }
        source.put("query", query);
        DcSearchHits hits = roleType.search(source).getHits();

        // 対象のRoleが存在しない場合はNull
        if (hits == null || hits.getCount() == 0) {
            DcCoreLog.Dav.ROLE_NOT_FOUND.params("Not Hit").writeLog();
            throw DcCoreException.Dav.ROLE_NOT_FOUND;
        }
        // 対象のRoleが複数件取得された場合は内部エラーとする
        if (hits.getAllPages() > 1) {
            DcCoreLog.OData.FOUND_MULTIPLE_RECORDS.params(hits.getAllPages()).writeLog();
            throw DcCoreException.OData.DETECTED_INTERNAL_DATA_CONFLICT;
        }

        DcSearchHit hit = hits.getHits()[0];
        return hit.getId();
    }

    /**
     * ロールIDからロールリソースURLを取得. jsonObjのロールIDをロールリソースURLに置換する
     * @param jsonObj ID置換後のJSON
     * @param baseUrlStr xml:base値
     */
    @SuppressWarnings("unchecked")
    private void roleIdToName(Object jsonObj, String baseUrlStr) {

        JSONArray array = new JSONArray();
        if (jsonObj instanceof JSONObject) {
            array.add(jsonObj);
        } else {
            array = (JSONArray) jsonObj;
        }
        if (array != null) {
            // xml:base対応
            for (int i = 0; i < array.size(); i++) {
                JSONObject aceJson = (JSONObject) array.get(i);
                JSONObject principal = (JSONObject) aceJson.get(KEY_ACL_PRINCIPAL);
                if (principal.get(KEY_ACL_HREF) != null) {
                    // ロールIDに該当するロール名が無かった場合はロールが削除済みと判断し、無視する。
                    String roloResourceUrl = roleIdToRoleResourceUrl((String) principal.get(KEY_ACL_HREF));
                    if (roloResourceUrl == null) {
                        // ロールIDに該当するロール名が無かった場合はロールが削除済みと判断し、ACEタグごと削除する。
                        array.remove(i);
                        --i;
                        // すべてのロールIDが削除済みの場合、空のACEタグに変更する
                        if (array.isEmpty() && jsonObj instanceof JSONObject) {
                            JSONObject objJson = (JSONObject) jsonObj;
                            objJson.clear();
                        }
                        continue;
                    }
                    // base:xml値からロールリソースURLの編集
                    roloResourceUrl = baseUrlToRoleResourceUrl(baseUrlStr, roloResourceUrl);
                    principal.put(KEY_ACL_HREF, roloResourceUrl);
                } else if (principal.get(KEY_ACL_ALL) != null) {
                    principal.put(KEY_ACL_ALL, null);
                }
            }
        }
    }

    /**
     * ロールIDからロール名を取得.
     * @param roleId ロールID
     * @return ロール名
     */
    @SuppressWarnings("unchecked")
    private String roleIdToRoleResourceUrl(String roleId) {
        String boxName = null;
        String schema = null;

        EntitySetAccessor roleType = EsModel.cellCtl(this.cell, Role.EDM_TYPE_NAME);
        DcGetResponse hit = roleType.get(roleId);

        if (hit == null || !hit.isExists()) {
            // ロールが存在しない場合、nullを返す。
            return null;
        }
        Map<String, Object> role = hit.getSource();
        Map<String, Object> s = (Map<String, Object>) role.get(DavNode.KEY_PARENT);
        Map<String, Object> l = (Map<String, Object>) role.get(KEY_LINK);
        String roleName = (String) s.get(KEY_NAME);
        String boxId = (String) l.get(Box.EDM_TYPE_NAME);
        if (boxId != null) {
            // Boxの検索
            Map<String, Object> boxsrc = searchBox(this.cell, boxId);
            Map<String, Object> boxs = (Map<String, Object>) boxsrc.get("s");
            boxName = (String) boxs.get(KEY_NAME);
            schema = (String) boxs.get(KEY_SCHEMA);
        }
        Role roleObj = new Role(roleName, boxName, schema, this.cell.getUrl());
        return roleObj.createUrl();
    }

    /**
     * PROPFINDのACL内のxml:base値を生成します.
     * @return
     */
    private String createBaseUrlStr() {
        String result = null;
        if (this.box != null) {
            // Boxレベル以下のACLの場合、BoxリソースのURL
            // セルURLは連結でスラッシュつけてるので、URLの最後がスラッシュだったら消す。
            result = String.format(Role.ROLE_RESOURCE_FORMAT, this.cell.getUrl().replaceFirst("/$", ""),
                    this.box.getName(), "");
        } else {
            // Cellレベル以下のACLの場合、デフォルトBoxのリソースURL
            // セルURLは連結でスラッシュつけてるので、URLの最後がスラッシュだったら消す。
            result = String.format(Role.ROLE_RESOURCE_FORMAT, this.cell.getUrl().replaceFirst("/$", ""),
                    Box.DEFAULT_BOX_NAME, "");
        }
        return result;
    }

    /**
     * xml:baseに従ってRoleResorceUrlの整形.
     * @param baseUrlStr xml:baseの値
     * @param roloResourceUrl ロールリソースURL
     * @return
     */
    private String baseUrlToRoleResourceUrl(String baseUrlStr, String roloResourceUrlStr) {
        String result = null;
        Role baseUrl = null;
        Role roloResourceUrl = null;
        try {
            // base:xmlはロールリソースURLではないため、ダミーで「__」を追加
            baseUrl = new Role(new URL(baseUrlStr + "__"));
            roloResourceUrl = new Role(new URL(roloResourceUrlStr));
        } catch (MalformedURLException e) {
            throw DcCoreException.Dav.ROLE_NOT_FOUND.reason(e);
        }
        if (baseUrl.getBoxName().equals(roloResourceUrl.getBoxName())) {
            // base:xmlのBOXとロールリソースURLのBOXが同じ場合
            result = roloResourceUrl.getName();
        } else {
            // base:xmlのBOXとロールリソースURLのBOXが異なる場合
            result = String.format(ACL_RELATIVE_PATH_FORMAT, roloResourceUrl.getBoxName(), roloResourceUrl.getName());
        }
        return result;
    }

    static final String KEY_LINK = "l";
    static final String KEY_CONTENT_TYPE = "ct";
    static final String KEY_CONTENT_LENGTH = "length";
    static final String KEY_BASE64 = "b64";
    static final String KEY_NAME = "Name";
    static final String KEY_SCHEMA = "Schema";
    static final String KEY_ACL_PRINCIPAL = "D.principal";
    static final String KEY_ACE = "D.ace";
    static final String KEY_ACL_HREF = "D.href";
    static final String KEY_ACL_ALL = "D.all";
    static final String KEY_ACL_BASE = "@base";
    static final String KEY_REQUIRE_SCHEMA_AUTHZ = "@requireSchemaAuthz";
    static final String ACL_RELATIVE_PATH_FORMAT = "../%s/%s";

    @Override
    public String getId() {
        return this.nodeId;
    }

    /**
     * IDのセッター.
     * @param paramNodeId ノードID
     */
    public void setId(String paramNodeId) {
        this.nodeId = paramNodeId;
    }

    /**
     * @return cell id
     */
    public String getCellId() {
        return this.cell.getId();
    }

    /**
     * DavNodeのゲッター.
     * @return DavNode
     */
    public DavNode getDavNode() {
        return this.davNode;
    }

    @Override
    public Long getVersion() {
        return this.version;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> getSource() {
        return this.davNode.getSource();
    }

    /**
     * BoxIdでEsを検索する.
     * @param cellObj Cell
     * @param boxId ボックスId
     * @return 検索結果
     */
    public static Map<String, Object> searchBox(final Cell cellObj, final String boxId) {

        EntitySetAccessor boxType = EsModel.box(cellObj);
        DcGetResponse getRes = boxType.get(boxId);
        if (getRes == null || !getRes.isExists()) {
            DcCoreLog.Dav.ROLE_NOT_FOUND.params("Box Id Not Hit").writeLog();

            throw DcCoreException.Dav.ROLE_NOT_FOUND;
        }
        return getRes.getSource();
    }
}
