package com.qiniu.qbox.up;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.zip.CRC32;

import org.apache.commons.codec.binary.Base64;

import com.qiniu.qbox.Config;
import com.qiniu.qbox.auth.CallRet;
import com.qiniu.qbox.auth.Client;

public class UpService {
	private static final int INVALID_CTX = 701;
	private Client conn;
	
	public UpService(Client conn) {
		this.conn = conn;
	}
	
	public ResumablePutRet makeBlock(String upHost, long blockSize, byte[] body, long bodyLength) {
		CallRet ret = this.conn.callWithBinary(upHost + "/mkblk/" + String.valueOf(blockSize), "application/octet-stream", body, bodyLength);
		ResumablePutRet putRet = new ResumablePutRet(ret) ;
		return putRet;
	}

	public ResumablePutRet putBlock(String upHost, long blockSize, String ctx, long offset, byte[] body, long bodyLength) {
		CallRet ret = this.conn.callWithBinary(upHost + "/bput/" + ctx + "/" + String.valueOf(offset), "application/octet-stream", body, bodyLength);
		
		return new ResumablePutRet(ret);
	}
	
	public CallRet makeFile(String upHost, String cmd, String entry, long fsize, String params, String callbackParams, String[] checksums) {
		
		if (callbackParams != null && !callbackParams.isEmpty()) {
			params += "/params/" + new String(Base64.encodeBase64(callbackParams.getBytes(), false, false));
		}
		
		String url = upHost + cmd + Client.urlsafeEncodeString(entry.getBytes()) + "/fsize/" + String.valueOf(fsize) + params;
		
		byte[] body = new byte[20 * checksums.length];
		
		for (int i = 0; i < checksums.length; i++) {
			byte[] buf = Base64.decodeBase64(checksums[i]);
			System.arraycopy(buf,  0, body, i * 20, buf.length);
		}
		
		CallRet ret = this.conn.callWithBinary(url, null, body, body.length);
		
		return ret;
	}
	
	public static int blockCount(long fsize) {
		return (int)((fsize + Config.BLOCK_SIZE - 1) / Config.BLOCK_SIZE);
	}

	/**
	 * Put a single block.
	 * 
	 * @param in
	 * @param blockIndex
	 * @param blockSize
	 * @param chunkSize
	 * @param retries If fails, how many times should it retry to upload?
	 * @param progress
	 * @param notifier
	 */
	public ResumablePutRet resumablePutBlock(String upHost, RandomAccessFile file, 
			int blockIndex, long blockSize, long chunkSize, 
			int retryTimes,
			BlockProgress progress, BlockProgressNotifier notifier) {
		
		ResumablePutRet ret = null;

		if (progress.context == null || progress.context.isEmpty()) { // This block has never been uploaded.
			int bodyLength = (int)((blockSize > chunkSize) ? chunkSize : blockSize); // Smaller one.
			
			byte[] body = new byte[bodyLength];
			
			try {
				file.seek(Config.BLOCK_SIZE * blockIndex);
				int readBytes = file.read(body, 0, bodyLength);
				if (readBytes != bodyLength) { // Didn't get expected content.
					return new ResumablePutRet(new CallRet(400, "Read nothing"));
				}
				
				ret = makeBlock(upHost, (int)blockSize, body, bodyLength);
				upHost = ret.getHost() ;
				if (!ret.ok()) {
					// Error handling
					return ret;
				}
				
				CRC32 crc32 = new CRC32();
				crc32.update(body, 0, bodyLength);
				
				if (ret.getCrc32() != crc32.getValue()) {
					// Something wrong!
					ret.statusCode = 400;
					return ret;
				}
				
				progress.context = ret.getCtx();
				progress.offset = bodyLength;
				progress.restSize = blockSize - bodyLength;
				progress.host = upHost;
				
				notifier.notify(blockIndex, progress);
				
			} catch (IOException e) {
				e.printStackTrace();
				return new ResumablePutRet(new CallRet(400, e));
			}
		} else if (progress.offset + progress.restSize != blockSize) {
			// Invalid arg.
			return new ResumablePutRet(new CallRet(400, "Invalid arg. File length does not match"));
		}
		
		while (progress.restSize > 0) {
			int bodyLength = (int)((chunkSize < progress.restSize) ? chunkSize : progress.restSize);
			
			byte[] body = new byte[bodyLength];
			
			for (int retries = retryTimes; retries > 0; --retries) {
			
				try {
					
					file.seek(blockIndex * Config.BLOCK_SIZE + progress.offset);
					int readBytes = file.read(body, 0, bodyLength);
					if (readBytes != bodyLength) { // Didn't get anything
						return new ResumablePutRet(new CallRet(400, "Read nothing"));
					}
					
					ret = putBlock(upHost, blockSize, progress.context, progress.offset, body, bodyLength);
					if (ret.ok()) {
						
						CRC32 crc32 = new CRC32();
						crc32.update(body, 0, bodyLength);
						
						if (ret.getCrc32() == crc32.getValue()) {
		
							progress.context = ret.getCtx();
							progress.offset += bodyLength;
							progress.restSize -= bodyLength;
							progress.host = upHost;
							
							notifier.notify(blockIndex, progress);
						
							break; // Break to while loop.
						}
					} else if (ret.getStatusCode() == INVALID_CTX) {
						// invalid context
						progress.context = "";
						notifier.notify(blockIndex, progress);

						return ret;
					}
				} catch (IOException e) {
					e.printStackTrace();
					return new ResumablePutRet(new CallRet(400, e));
				}
			}
		}
		if (ret == null) {
			ret = new ResumablePutRet(new CallRet(400, (String)null));
		}
		return ret;
	}
	
	public ResumablePutRet resumablePut(RandomAccessFile file, long fsize,
			String[] checksums, BlockProgress[] progresses, 
			ProgressNotifier progressNotifier, BlockProgressNotifier blockProgressNotifier) {
		
		String upHost = Config.UP_HOST;
		if (progresses[0] != null) {
			upHost = progresses[0].host;
		}
		ResumablePutRet ret = null ;
		int blockCount = blockCount(fsize);
		
		if (checksums.length != blockCount || progresses.length != blockCount) {
			return new ResumablePutRet(new CallRet(400, "Invalid arg. Unexpected block count."));
		}
		
		for (int i = 0; i < blockCount; i++) {
			if (checksums[i] == null || checksums[i].isEmpty()) {
				int blockIndex = i;
				long blockSize = Config.BLOCK_SIZE;
				if (blockIndex == blockCount - 1) {
					blockSize = fsize - Config.BLOCK_SIZE * blockIndex;
				}
				
				if (progresses[i] == null) {
					progresses[i] = new BlockProgress();
				}
				
				ret = resumablePutBlock(upHost, file, 
						blockIndex, blockSize, Config.PUT_CHUNK_SIZE, 
						Config.PUT_RETRY_TIMES, 
						progresses[i], 
						blockProgressNotifier);
				
				if (i == 0) {
						upHost = ret.getHost();
				}
				if (!ret.ok()) {
					return ret;
				}
				
				checksums[i] = ret.getChecksum();
				
				progressNotifier.notify(i, checksums[i]);
			}
		}
		
		ret = new ResumablePutRet(new CallRet(200, (String)null));
		ret.setHost(upHost);
		return ret;
	}
}
