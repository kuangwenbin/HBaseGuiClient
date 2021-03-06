package cz.zcu.kiv.hbaseguiclient.model;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.filter.PageFilter;
import org.apache.hadoop.hbase.util.Bytes;

public class CommandModel {

	static Map<String, Map<String, String>> queryResult;
	static Set<String> columns;
	public static Table currentTableInterface;

	public static Map<String, Map<String, String>> getQueryResult() {
		return queryResult;
	}

	public static Set<String> getColumns() {
		return columns;
	}

	//@TODO gramatics
	static Pattern scanPattern = Pattern.compile("\\s*scan\\s+(?<table>[\\w:@.]+)(\\s+start\\s+(?<start>[\\w\\s]+))?(\\s+limit\\s+(?<limit>\\d+))?(\\s+skip\\s+(?<skip>\\d+))?\\s*",
			Pattern.CASE_INSENSITIVE);

	public static String submitQuery(String text, boolean hexConversion) throws IOException {

		Matcher m = scanPattern.matcher(text);

		if (m.matches()) {
			String fullTableIdentifier = m.group("table");
			String skip = m.group("skip");
			String limit = m.group("limit");
			String start = m.group("start");

			String[] tableLocators = fullTableIdentifier.split("@");

			String cluster;
			String table;
			if (tableLocators.length > 1) {
				cluster = tableLocators[0];
				table = tableLocators[1];
			} else {
				cluster = getFirstClusterAlias();
				table = tableLocators[0];
			}

			if (cluster == null) {
				return "Cluster not specified";
			}

			if (table == null) {
				return "Table not specified";
			}

			/*if (!AppContext.clusterTables.get(cluster).get("default").contains(table)) {
				return "Table not found at cluster " + cluster;
			}*/
			try{
				execScan(cluster, table, start, skip, limit, hexConversion);
			}catch (Exception e){
				e.printStackTrace();
				return "Execute scan error " + e.getMessage();
			}
			//@TODO table namespace (HBase v1.0+)
			return null;
		} else {
			return "No table found. Exec command like \"scan Table limit 10\"";
		}
	}

	public static String getFirstClusterAlias() {
		for (String cluster : AppContext.clusterTables.keySet()) {
			return cluster;
		}
		return null;
	}

	private static void execScan(String cluster, String table, String start, String skip, String limit, boolean hexConversion) throws IOException {
		Scan scan = new Scan();
		scan.setCaching(60);
		scan.setMaxResultSize(1 * 1024 * 1024); //1MB

		if (start != null) {
			scan.setStartRow(Bytes.toBytes(start));
		}

		int limitInt = 40; //default limit
		AtomicInteger skipInt = new AtomicInteger(0);

		if (skip != null) {
			skipInt.set(Integer.parseInt(skip));
		}
		if (limit != null) {
			limitInt = Integer.parseInt(limit);
		}
		scan.setFilter(new PageFilter(limitInt + skipInt.get()));

		Connection connection = AppContext.clusterMap.get(cluster).getKey();
		//HTableInterface tableInterface = connection.getTable(table);
		Table tableInterface = connection.getTable(TableName.valueOf(table));
		currentTableInterface = tableInterface;

		//rowKey cf:cq	value
		queryResult = new TreeMap<>();
		columns = new TreeSet<>(); //store all columns from result (some could be null in first line)

		ResultScanner resultScanner = tableInterface.getScanner(scan);

		resultScanner.iterator().forEachRemaining(res -> {

			if (skipInt.getAndDecrement() <= 0) {

				Map<String, String> columnValues = new HashMap<>();

				res.getNoVersionMap().forEach((familyBytes, qualifierValue) -> {
					String family = Bytes.toString(familyBytes);
					qualifierValue.forEach((qualifierBytes, valueBytes) -> {

						String qualifier = Bytes.toString(qualifierBytes);
						String value;
						if (hexConversion) {
							value = Bytes.toHex(valueBytes);
						} else {
							value = Bytes.toString(valueBytes);
						}

						columns.add(family + ":" + qualifier);
						columnValues.put(family + ":" + qualifier, value);
					});
				});

				String rk;
				if (hexConversion) {
					rk = Bytes.toHex(res.getRow());
				} else {
					rk = Bytes.toString(res.getRow());
				}
				queryResult.put(rk, columnValues);
			}
		});
		/*for(Result res :resultScanner){
			System.out.println(res);
		}*/
	}

}
