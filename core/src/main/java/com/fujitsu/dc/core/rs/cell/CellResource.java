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
package com.fujitsu.dc.core.rs.cell;

import java.io.IOException;
import java.io.Reader;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;

import org.apache.wink.webdav.WebDAVMethod;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fujitsu.dc.common.es.util.IndexNameEncoder;
import com.fujitsu.dc.common.utils.DcCoreUtils;
import com.fujitsu.dc.core.DcCoreConfig;
import com.fujitsu.dc.core.DcCoreException;
import com.fujitsu.dc.core.annotations.ACL;
import com.fujitsu.dc.core.auth.AccessContext;
import com.fujitsu.dc.core.auth.CellPrivilege;
import com.fujitsu.dc.core.cell.CellBulkDeletionRunner;
import com.fujitsu.dc.core.event.DcEvent;
import com.fujitsu.dc.core.event.EventBus;
import com.fujitsu.dc.core.model.Box;
import com.fujitsu.dc.core.model.Cell;
import com.fujitsu.dc.core.model.CellRsCmp;
import com.fujitsu.dc.core.model.DavCmp;
import com.fujitsu.dc.core.model.ModelFactory;
import com.fujitsu.dc.core.model.impl.es.EsModel;
import com.fujitsu.dc.core.model.impl.es.accessor.CellAccessor;
import com.fujitsu.dc.core.model.impl.es.accessor.CellDeleteAccessor;
import com.fujitsu.dc.core.model.impl.es.cache.CellCache;
import com.fujitsu.dc.core.model.impl.es.doc.CellDocHandler;
import com.fujitsu.dc.core.model.lock.CellLockManager;
import com.fujitsu.dc.core.model.lock.UnitUserLockManager;
import com.fujitsu.dc.core.rs.box.BoxResource;

/**
 * JAX-RS Resource handling DC Cell Level Api. /{cell名}というパスにきたときの処理.
 */
public final class CellResource {

    /**
     * ログ用オブジェクト.
     */
    static Logger log = LoggerFactory.getLogger(CellResource.class);

    Cell cell;
    DavCmp davCmp;
    CellRsCmp cellRsCmp;
    AccessContext accessContext;

    /**
     * コンストラクタ.
     * @param accessContext AccessContext
     */
    public CellResource(
            final AccessContext accessContext) {
        // Cellが存在しないときは例外
        this.accessContext = accessContext;
        this.cell = this.accessContext.getCell();
        if (this.cell == null) {
            throw DcCoreException.Dav.CELL_NOT_FOUND;
        }
        this.davCmp = ModelFactory.cellCmp(this.cell);
        this.cellRsCmp = new CellRsCmp(this.davCmp, this.cell, this.accessContext);
        checkReferenceMode();
    }

    private void checkReferenceMode() {
        Cell cellObj = accessContext.getCell();
        String unitPrefix = DcCoreConfig.getEsUnitPrefix();
        String owner = cellObj.getOwner();

        if (owner == null) {
            owner = "anon";
        } else {
            owner = IndexNameEncoder.encodeEsIndexName(owner);
        }
        if (UnitUserLockManager.hasLockObject(unitPrefix + "_" + owner)) {
            throw DcCoreException.Server.SERVICE_MENTENANCE_RESTORE;
        }
    }

    /*
     * static private Cache cache = CacheManager.getInstance().getCache("box-cache"); static {
     * cache.addPropertyChangeListener(new PropertyChangeListener() {
     * @Override public void propertyChange(PropertyChangeEvent arg0) {
     * System.out.println(arg0.toString()); } }); }
     */
    /**
     * ルートに対するGETメソッド.
     * @return JAX-RS Response Object
     */
    @GET
    public Response getSvcDoc() {
        StringBuffer sb = new StringBuffer();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<cell xmlns=\"urn:x-dc1:xmlns\">");
        sb.append("<uuid>" + this.cell.getId() + "</uuid>");
        sb.append("</cell>");
        return Response.ok().entity(sb.toString()).build();
    }

    /**
     * セル一括削除API.
     * @param recursiveHeader X-Dc-Recursiveヘッダ
     * @return JAX-RS Response Object
     */
    @DELETE
    public Response cellBulkDeletion(
            @HeaderParam(DcCoreUtils.HttpHeaders.X_DC_RECURSIVE) final String recursiveHeader) {
        // セル一括削除
        String cellId = this.cell.getId();
        String cellName = this.cell.getName();
        String unitUserName = this.cell.getUnitUserName();
        String cellInfoLog = String.format(" CellId:[%s], CellName:[%s], CellUnitUserName:[%s]", cellId, cellName,
                unitUserName);
        log.info("Cell Bulk Deletion." + cellInfoLog);

        // アクセス権限の確認を実施する
        // ユニットマスター、ユニットユーザ、ユニットローカルユニットユーザ以外は権限エラーとする
        String cellOwner = this.cell.getOwner();
        String accessType = this.accessContext.getType();
        checkAccessContextForCellBulkDeletion(cellOwner, accessType);

        // X-Dc-Recursiveヘッダの指定が"true"でない場合はエラーとする
        if (!"true".equals(recursiveHeader)) {
            throw DcCoreException.Misc.PRECONDITION_FAILED.params(DcCoreUtils.HttpHeaders.X_DC_RECURSIVE);
        }

        // Cellに対するアクセス数を確認して、アクセスをロックする
        int maxLoopCount = Integer.valueOf(DcCoreConfig.getCellLockRetryTimes());
        long interval = Long.valueOf(DcCoreConfig.getCellLockRetryInterval());
        waitCellAccessible(cellId, maxLoopCount, interval);

        CellLockManager.setBulkDeletionStatus(cellId);

        // Cellエンティティを削除する
        CellAccessor cellAccessor = (CellAccessor) EsModel.cell();
        CellDocHandler docHandler = new CellDocHandler(cellAccessor.get(cell.getId()));
        try {
            cellAccessor.delete(docHandler);
            log.info("Cell Entity Deletion End.");
        } finally {
            CellCache.clear(this.cell.getName());
            CellLockManager.resetBulkDeletionStatus(cellId);
        }

        // Cell削除管理テーブルに削除対象のDB名とセルIDを追加する
        insertCellDeleteRecord(unitUserName, cellId);

        // 非同期でWebDavファイル、EventLogファイル、ESのCell配下のエンティティを削除する
        // MySQLのCell配下のエンティティはバッチにて削除する
        CellBulkDeletionRunner runner = new CellBulkDeletionRunner(cell);
        Thread thread = new Thread(runner);
        thread.start();

        // 204を返却する
        return Response.noContent().build();
    }

    private void insertCellDeleteRecord(String unitUserName, String cellId) {
        CellDeleteAccessor accessor = new CellDeleteAccessor();
        if (!accessor.isValid()) {
            log.warn(String.format("Insert CELL_DELETE Record To Ads Failed. db_name:[%s], cell_id:[%s]",
                    unitUserName, cellId));
            return;
        }
        accessor.createManagementDatabase();
        accessor.insertCellDeleteRecord(unitUserName, cellId);
    }

    private void waitCellAccessible(String cellId, int maxLoopCount, long interval) {
        for (int loopCount = 0; loopCount < maxLoopCount; loopCount++) {
            long count = CellLockManager.getReferenceCount(cellId);
            // 自分のリクエスト分も含まれるので他のリクエストが存在する場合は１より大きくなる
            if (count <= 1) {
                return;
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                throw DcCoreException.Misc.CONFLICT_CELLACCESS;
            }
        }
        throw DcCoreException.Misc.CONFLICT_CELLACCESS;
    }

    private void checkAccessContextForCellBulkDeletion(String cellOwner, String accessType) {
        // ユニットマスター、ユニットユーザ、ユニットローカルユニットユーザ以外は権限エラーとする
        if (AccessContext.TYPE_INVALID.equals(accessType)) {
            this.accessContext.throwInvalidTokenException();
        } else if (AccessContext.TYPE_ANONYMOUS.equals(accessType)) {
            throw DcCoreException.Auth.AUTHORIZATION_REQUIRED;
        } else if (!AccessContext.TYPE_UNIT_MASTER.equals(accessType)
                && !AccessContext.TYPE_UNIT_USER.equals(accessType)
                && !AccessContext.TYPE_UNIT_LOCAL.equals(accessType)) {
            throw DcCoreException.Auth.UNITUSER_ACCESS_REQUIRED;
        } else if (AccessContext.TYPE_UNIT_USER.equals(accessType)
                || AccessContext.TYPE_UNIT_LOCAL.equals(accessType)) {
            // ユニットユーザ、ユニットローカルユニットユーザの場合はセルオーナとアクセス主体が一致するかチェック
            String subject = this.accessContext.getSubject();
            if (cellOwner == null || !cellOwner.equals(subject)) {
                throw DcCoreException.Auth.NOT_YOURS;
            }
        }
    }

    /**
     * @param dcCredHeader dcCredHeader X-Dc-Credentialヘッダ
     * @return CellCtlResource
     */
    @Path("__ctl")
    public CellCtlResource ctl(@HeaderParam(DcCoreUtils.HttpHeaders.X_DC_CREDENTIAL) final String dcCredHeader) {
        return new CellCtlResource(this.accessContext, dcCredHeader, this.cellRsCmp);
    }

    /**
     * パスワード変更APIのエンドポイント.
     * @param dcCredHeader dcCredHeader
     * @return Response
     */
    @Path("__mypassword")
    public PasswordResource mypassword(
            @HeaderParam(DcCoreUtils.HttpHeaders.X_DC_CREDENTIAL) final String dcCredHeader) {
        return new PasswordResource(this.accessContext, dcCredHeader, this.cell);
    }

    /**
     * 認証のエンドポイント .
     * <ul>
     * <li>dc_targetにURLが書いてあれば、そのCELLをTARGETのCELLとしてtransCellTokenを発行する。</li>
     * <li>scopeがなければCellLocalを発行する。</li>
     * </ul>
     * @return TokenEndPointResourceオブジェクト
     */
    @Path("__auth")
    public TokenEndPointResource auth() {
        return new TokenEndPointResource(this.cell, this.cellRsCmp);
    }

    /**
     * ImplicitFlow認証のエンドポイント .
     * <ul>
     * <li>dc_targetにURLが書いてあれば、そのCELLをTARGETのCELLとしてtransCellTokenを発行する。</li>
     * </ul>
     * @return AuthzEndPointResourceオブジェクト
     */
    @Path("__authz")
    public AuthzEndPointResource authz() {
        return new AuthzEndPointResource(this.cell, this.cellRsCmp);
    }

    /**
     * Htmlによるエラー応答のエンドポイント .
     * @return AuthzEndPointResourceオブジェクト
     */
    @Path("__html/error")
    public ErrorHtmlResource errorHtml() {
        return new ErrorHtmlResource();
    }

    /**
     * ロールのエンドポイント .
     * @return RoleResourceオブジェクト
     */
    @Path("__role")
    public RoleResource role() {
        return new RoleResource(this.cell, this.cellRsCmp);
    }

    /**
     * BoxURL取得のエンドポイント .
     * @return BoxUrlResourceオブジェクト
     */
    @Path("__box")
    public BoxUrlResource boxUrl() {
        return new BoxUrlResource(this.cell, this.accessContext);
    }

    /**
     * イベント受付のエンドポイント .
     * @param boxName Box名
     * @param reader 入力
     * @return JAXRS応答
     */
    @POST
    @Path("__event/{boxName}")
    public Response postEvent(
            @PathParam("boxName") final String boxName,
            final Reader reader) {
        // アクセス制御
        this.cellRsCmp.checkAccessContext(this.cellRsCmp.getAccessContext(), CellPrivilege.EVENT);
        // Subjectを取得する
        String subject = this.accessContext.getSubject();
        // BoxNameからSchemaをとる
        Box box = this.cell.getBoxForName(boxName);
        String schema = null;
        if (box != null) {
            schema = box.getSchema();
        }
        // Bodyを解釈してDcEventオブジェクトをつくる。
        JSONParser parser = new JSONParser();
        try {
            JSONObject evJson = (JSONObject) parser.parse(reader);
            String levelStr = (String) evJson.get("level");
            String action = (String) evJson.get("action");
            String object = (String) evJson.get("object");
            String result = (String) evJson.get("result");
            int level = DcEvent.Level.INFO;
            if ("warn".equalsIgnoreCase(levelStr)) {
                level = DcEvent.Level.WARN;
            } else if ("error".equalsIgnoreCase(levelStr)) {
                level = DcEvent.Level.ERROR;
            }
            DcEvent ev = new DcEvent("client", schema, level, subject, action, object, result);

            // DcEventオブジェクトをEventBusオブジェクトに流す.
            EventBus eventBus = this.cell.getEventBus();
            eventBus.post(ev);
            return Response.ok().build();
        } catch (IOException e) {
            throw DcCoreException.Server.UNKNOWN_ERROR.reason(e);
        } catch (ParseException e) {
            throw DcCoreException.Event.JSON_PARSE_ERROR.reason(e);
        }

    }

    /**
     * イベントAPIのエンドポイント.
     * @return EventResourceオブジェクト
     */
    @Path("__event")
    public EventResource event() {
        return new EventResource(this.cell, this.accessContext, this.cellRsCmp);
    }

    /**
     * ログ取り出しのエンドポイント .
     * @return JAXRS応答
     */
    @Path("__log")
    public LogResource log() {
        return new LogResource(this.cell, this.accessContext, this.cellRsCmp);
    }

    /**
     * デフォルトボックスへのアクセス.
     * @param request HTPPサーブレットリクエスト
     * @param jaxRsRequest JAX-RS用HTTPリクエスト
     * @return BoxResource BoxResource Object
     */
    @Path("__")
    public BoxResource box(@Context final HttpServletRequest request,
            @Context final Request jaxRsRequest) {
        return new BoxResource(this.cell, Box.DEFAULT_BOX_NAME, this.accessContext,
                this.cellRsCmp, request, jaxRsRequest);
    }

    /**
     * メッセージ送信のエンドポイント .
     * @return MessageResourceオブジェクト
     */
    @Path("__message")
    public MessageResource message() {
        return new MessageResource(this.accessContext, this.cellRsCmp);

    }

    /**
     * 次のパスをBoxResourceへ渡すメソッド.
     * @param request HTPPサーブレットリクエスト
     * @param boxName Boxパス名
     * @param jaxRsRequest JAX-RS用HTTPリクエスト
     * @return BoxResource BoxResource Object
     */
    @Path("{box: [^\\/]+}")
    public BoxResource box(
            @Context final HttpServletRequest request,
            @PathParam("box") final String boxName,
            @Context final Request jaxRsRequest) {
        return new BoxResource(this.cell, boxName, this.accessContext, this.cellRsCmp, request, jaxRsRequest);
    }

    /**
     * PROPFINDメソッドの処理.
     * @param requestBodyXml Request Body
     * @param depth Depth Header
     * @param contentLength Content-Length Header
     * @param transferEncoding Transfer-Encoding Header
     * @return JAX-RS Response
     */
    @WebDAVMethod.PROPFIND
    public Response propfind(final Reader requestBodyXml,
            @DefaultValue("0") @HeaderParam(DcCoreUtils.HttpHeaders.DEPTH) final String depth,
            @HeaderParam(HttpHeaders.CONTENT_LENGTH) final Long contentLength,
            @HeaderParam("Transfer-Encoding") final String transferEncoding) {

        return this.cellRsCmp.doPropfind(requestBodyXml, depth, contentLength, transferEncoding,
                CellPrivilege.PROPFIND, CellPrivilege.ACL_READ);
    }

    /**
     * PROPPATCHメソッドの処理.
     * @param requestBodyXml Request Body
     * @return JAX-RS Response
     */
    @WebDAVMethod.PROPPATCH
    public Response proppatch(final Reader requestBodyXml) {
        AccessContext ac = this.cellRsCmp.getAccessContext();
        // トークンの有効性チェック
        // トークンがINVALIDでもACL設定でPrivilegeがallに設定されているとアクセスを許可する必要があるのでこのタイミングでチェック
        if (AccessContext.TYPE_INVALID.equals(ac.getType())) {
            ac.throwInvalidTokenException();
        } else if (AccessContext.TYPE_ANONYMOUS.equals(ac.getType())) {
            throw DcCoreException.Auth.AUTHORIZATION_REQUIRED;
        }

        // アクセス制御 CellレベルPROPPATCHはユニットユーザのみ可能とする
        if (!ac.isUnitUserToken()) {
            throw DcCoreException.Auth.UNITUSER_ACCESS_REQUIRED;
        }
        return this.cellRsCmp.doProppatch(requestBodyXml);
    }

    /**
     * ACLメソッドの処理.
     * @param reader 設定XML
     * @return JAX-RS Response
     */
    @ACL
    public Response acl(final Reader reader) {
        // アクセス制御
        this.cellRsCmp.checkAccessContext(this.cellRsCmp.getAccessContext(), CellPrivilege.ACL);
        return this.cellRsCmp.doAcl(reader);
    }

    /**
     * OPTIONSメソッド.
     * @return JAX-RS Response
     */
    @OPTIONS
    public Response options() {
        // アクセス制御
        this.cellRsCmp.checkAccessContext(this.cellRsCmp.getAccessContext(), CellPrivilege.SOCIAL_READ);
        return DcCoreUtils.responseBuilderForOptions(
                HttpMethod.POST,
                DcCoreUtils.HttpMethod.PROPFIND
                ).build();
    }

}
