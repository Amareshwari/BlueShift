/*
 *
 *  Copyright 2015 Flipkart Internet Pvt. Ltd.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.flipkart.fdp.migration.distcp.utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

import com.flipkart.fdp.migration.distcp.config.*;
import com.flipkart.fdp.migration.distcp.core.MirrorDCMImpl;
import com.flipkart.fdp.migration.distcp.core.MirrorInputSplit;
import com.flipkart.fdp.optimizer.OptimTuple;
import com.flipkart.fdp.optimizer.api.IInputJob;
import com.flipkart.fdp.optimizer.api.IJobLoadOptimizer;
import com.flipkart.fdp.optimizer.api.JobLoadOptimizerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.io.compress.Compressor;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import com.flipkart.fdp.migration.distcp.core.MirrorFileInputFormat;
import com.google.gson.Gson;

public class MirrorUtils {

	public static DCMConfig getConfigFromConf(Configuration conf) {
		String configString = conf.get(MirrorFileInputFormat.DCM_CONFIG);
		Gson gson = new Gson();
		DCMConfig config = gson.fromJson(configString, DCMConfig.class);
		return config;
	}

	public static void setConfigInConf(Configuration conf, DCMConfig config) {

		Gson gson = new Gson();
		String configString = gson.toJson(config);
		conf.set(MirrorFileInputFormat.DCM_CONFIG, configString);

	}

	public static String getCodecNameFromPath(Configuration conf, String path) {
		CompressionCodecFactory compressionCodecs = new CompressionCodecFactory(
				conf);
		CompressionCodec codec = compressionCodecs.getCodec(new Path(path));
		if (codec == null)
			return null;
		else
			return codec.getDefaultExtension();
	}

    public static List<InputSplit> optimizeInputSplits(Configuration conf, DCMConfig dcmConfig, Set<OptimTuple> locations, HashMap<String, MirrorDCMImpl.FileTuple> inputFileMap) throws Exception {
        DCMConstants.FileSystemType type = dcmConfig.getSinkConfig()
                            .getConnectionConfig().getType();
        switch (type) {

            case  MFTP:
                        return performMFTPOptimization(dcmConfig,locations,inputFileMap);

            case HDFS:
                         return perfotmHDFSOptimization(dcmConfig,locations,inputFileMap);

               default:
                   return perfotmHDFSOptimization(dcmConfig,locations,inputFileMap);
        }
    }

    private static List<InputSplit> perfotmHDFSOptimization(DCMConfig dcmConfig, Set<OptimTuple> locations, HashMap<String, MirrorDCMImpl.FileTuple> inputFileMap) {

        int numWorkers = locations.size();
        long totalBatchSize = 0l;
        List<InputSplit> splits = new ArrayList<InputSplit>();
        if (dcmConfig.getNumWorkers() > 0
                && dcmConfig.getNumWorkers() < numWorkers) {

            numWorkers = dcmConfig.getNumWorkers();

            for (Set<IInputJob> stats : optimizeWorkload(JobLoadOptimizerFactory.Optimizer.PRIORITY_QUEUE_BASED,
                                            locations,numWorkers)) {
                List<MirrorDCMImpl.FileTuple> tuple = new ArrayList<MirrorDCMImpl.FileTuple>();
                long size = 0;
                for (IInputJob stat : stats) {
                    tuple.add(inputFileMap.get(stat.getJobKey()));
                    size += stat.getJobSize();
                }
                totalBatchSize += size;
                splits.add(new MirrorInputSplit(tuple, size));
            }
        } else {
            for (OptimTuple stat : locations) {
                List<MirrorDCMImpl.FileTuple> tuple = new ArrayList<MirrorDCMImpl.FileTuple>();
                tuple.add(inputFileMap.get(stat.getJobKey()));
                splits.add(new MirrorInputSplit(tuple, stat.getJobSize()));
                totalBatchSize += stat.getJobSize();
            }
        }
        System.out.println("Total Batch Size : "+totalBatchSize);
        return splits;
    }


    private static List<InputSplit> performMFTPOptimization(DCMConfig config, Set<OptimTuple> locations, HashMap<String, MirrorDCMImpl.FileTuple> inputFileMap) throws Exception {
        int numWorkers = config.getSinkConfig()
                .getConnectionConfig().getHostConfigList().size();
        int index = 0;
        long availableSize;
        long requiredSize;
        long totalBatchSize = 0l;
        List<InputSplit> splits = new ArrayList<InputSplit>();
        List<HostConfig> hostConfigList = config.getSinkConfig()
                .getConnectionConfig().getHostConfigList();
        Collections.sort(hostConfigList,new Comparator<HostConfig>() {
            @Override
            public int compare(HostConfig o1, HostConfig o2) {
                return o1.compareTo(o2);
            }
        });
        Collections.reverse(hostConfigList);
        if( inputFileMap.size() < numWorkers  )
            numWorkers = inputFileMap.size();


        for( Set<IInputJob> stats : optimizeWorkload(JobLoadOptimizerFactory.Optimizer.PTASOPTMIZER,
                                            locations,numWorkers) ) {
            requiredSize = 0l;
            availableSize = hostConfigList.get(index).getFreeSpaceInBytes();
            List<MirrorDCMImpl.FileTuple> tuple = new ArrayList<MirrorDCMImpl.FileTuple>();
            for (IInputJob stat : stats) {
                tuple.add(inputFileMap.get(stat.getJobKey()));
                requiredSize += stat.getJobSize();
            }
            totalBatchSize += requiredSize;
            if( requiredSize > availableSize )
                throw new Exception("Total Files size is more than available space on disk! ");
            else
                splits.add(new MirrorInputSplit(tuple,requiredSize,hostConfigList.get(index++)));

        }
        System.out.println("Total Batch Size : "+totalBatchSize);

        return splits;
    }

    public static OutputStream getCodecOutputStream(Configuration conf,
			DCMConfig config, String outPath, OutputStream out)
			throws IOException {
		if (config.getSinkConfig().isUseCompression()) {

			CompressionCodecFactory compressionCodecs = new CompressionCodecFactory(
					conf);
			CompressionCodec codec = compressionCodecs.getCodecByName(config
					.getSinkConfig().getCompressionCodec());
			Compressor compressor = codec.createCompressor();
			out = codec.createOutputStream(out, compressor);
		}
		return out;
	}

	public static InputStream getCodecInputStream(Configuration conf,
			DCMConfig config, String inPath, InputStream in) throws IOException {

		CompressionCodecFactory compressionCodecs = new CompressionCodecFactory(
				conf);
		CompressionCodec codec = compressionCodecs.getCodec(new Path(inPath));
		if (codec == null)
			return in;
		Decompressor compressor = codec.createDecompressor();
		in = codec.createInputStream(in, compressor);

		return in;
	}

	public static MD5Digester copy(InputStream input, OutputStream result,
			TaskAttemptContext context) throws IOException {

		byte[] buffer = new byte[65536]; // 8K=8192 12K=12288 64K=65536
		long count = 0L;
		int n;

		long sts = System.currentTimeMillis();
		MD5Digester digester = new MD5Digester();


        while (-1 != (n = input.read(buffer))) {

            result.write(buffer, 0, n);

            digester.updateMd5digester(buffer, 0, n);
            count += n;

            if (count % 67108864 == 0) {
                System.out.println("Wrote 64M Data to Destination Total: "
                        + count + ", Time Taken(ms): "
                        + (System.currentTimeMillis() - sts));

                sts = System.currentTimeMillis();
                context.progress();
            }
        }

		System.out.println("Transfer Complete Total: " + count
				+ ", Time Taken(ms): " + (System.currentTimeMillis() - sts));
		return digester;
	}

//    public static MD5Digester copy(String destPath,InputStream input,
//                                   TaskAttemptContext context,DistFTPClient ftpClient) throws Exception {
//        long size;
//        long sts = System.currentTimeMillis();
//        MD5Digester digester = new MD5Digester();
//
//        if ( ftpClient.transferFile(destPath, input)) {
//            size = ftpClient.getFileSize(destPath);
//            System.out.println("Transfer Complete Total: " + size + "Host : " + ftpClient.getHostName()
//                    + ", Time Taken(ms): " + (System.currentTimeMillis() - sts));
//        }else
//               throw new Exception("Transfer of "+ destPath +" ---> "+ftpClient.getHostName()+" was Un Sucessfull!!!");
//
//        context.progress();
//
//        digester.updateMd5digester(Longs.toByteArray(size));
//
//        return digester;
//    }

	public static Set<String> getFileAsLists(String fileName) {
		Set<String> files = new HashSet<String>();
		if (fileName != null && fileName.trim().length() > 1) {
			try {
				BufferedReader reader = new BufferedReader(new FileReader(
						fileName));
				String line = null;
				while ((line = reader.readLine()) != null) {
					if (line.trim().length() <= 1 || line.startsWith("#"))
						continue;

					files.add(line);
				}
				reader.close();
			} catch (Exception e) {
				System.out.println("Ignoring FileSet from: " + fileName);
				System.out.println("Excpetion: " + e.getMessage());
			}
		}
		return files;
	}

	public static Set<String> getStringAsLists(String flist) {
		Set<String> files = new HashSet<String>();
		if (flist == null || flist.trim().length() <= 0)
			return files;
		String fileList[] = flist.split(",");

		for (String file : fileList)
			files.add(file);
		return files;
	}

	public static String getListAsString(Collection<String> flist) {
		StringBuilder builder = new StringBuilder();
		if (flist == null || flist.size() <= 0)
			return builder.toString();
		int count = 0;
		for (String file : flist) {
			if (count > 0)
				builder.append(",");
			builder.append(file);
			count++;
		}

		return builder.toString();
	}

	public static String stripExtension(String path) {
		if (path.lastIndexOf('.') < 1)
			return path;
		return path.substring(0, path.lastIndexOf('.'));
	}

	public static String getSimplePath(Path path) {
		return path.toUri().getPath();
	}

    public static List<Set<IInputJob>> optimizeWorkload(JobLoadOptimizerFactory.Optimizer optimizer,Set<OptimTuple> tasks,
                                                 int numMappers) {
        System.out.println("Total Tasks: " + tasks.size() + " Total Mappers: "
                + numMappers);

        System.out.println("Using Optimizer: " + optimizer.toString());
        IJobLoadOptimizer iJobLoadOptimizer = JobLoadOptimizerFactory
                .getJobLoadOptimizerFactory(optimizer);
        List<Set<IInputJob>> optimizedLoadSets = iJobLoadOptimizer
                .getOptimizedLoadSets(tasks, numMappers);
        return optimizedLoadSets;
    }
}
