import boto3
import pandas as pd
import requests
import xml.etree.ElementTree as ET
import json
import asyncio
import aiohttp
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Dict, List, Optional, Any
import logging
from dataclasses import dataclass
from io import BytesIO

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

@dataclass
class ProcessingResult:
    """Data class to hold processing results"""
    s3_key: str
    excel_data: Dict[str, Any]
    api_data: Dict[str, Any]
    combined_metadata: Dict[str, Any]
    success: bool
    error_message: Optional[str] = None

class S3ExcelAPIOrchestrator:
    def __init__(self, 
                 s3_bucket_name: str,
                 excel_file_path: str,
                 api_base_url: str,
                 excel_match_column: str,
                 aws_access_key_id: str = None,
                 aws_secret_access_key: str = None,
                 aws_region: str = 'us-east-1'):
        """
        Initialize the orchestrator
        
        Args:
            s3_bucket_name: Name of the S3 bucket
            excel_file_path: Local path to Excel file or S3 path
            api_base_url: Base URL for the API
            excel_match_column: Column name to match against S3 keys
            aws_access_key_id: AWS access key (optional if using IAM roles)
            aws_secret_access_key: AWS secret key (optional if using IAM roles)
            aws_region: AWS region
        """
        self.s3_bucket_name = s3_bucket_name
        self.excel_file_path = excel_file_path
        self.api_base_url = api_base_url
        self.excel_match_column = excel_match_column
        
        # Initialize S3 client
        session = boto3.Session(
            aws_access_key_id=aws_access_key_id,
            aws_secret_access_key=aws_secret_access_key,
            region_name=aws_region
        )
        self.s3_client = session.client('s3')
        
        # Load Excel data
        self.excel_df = self._load_excel_data()
        
    def _load_excel_data(self) -> pd.DataFrame:
        """Load Excel data from local file or S3"""
        try:
            if self.excel_file_path.startswith('s3://'):
                # Load from S3
                bucket_name = self.excel_file_path.split('/')[2]
                key = '/'.join(self.excel_file_path.split('/')[3:])
                
                obj = self.s3_client.get_object(Bucket=bucket_name, Key=key)
                excel_data = obj['Body'].read()
                df = pd.read_excel(BytesIO(excel_data))
            else:
                # Load from local file
                df = pd.read_excel(self.excel_file_path)
            
            logger.info(f"Loaded Excel data with {len(df)} rows")
            return df
            
        except Exception as e:
            logger.error(f"Failed to load Excel data: {str(e)}")
            raise
    
    def get_s3_keys(self, prefix: str = '', max_keys: int = 1000) -> List[str]:
        """Get list of keys from S3 bucket"""
        try:
            response = self.s3_client.list_objects_v2(
                Bucket=self.s3_bucket_name,
                Prefix=prefix,
                MaxKeys=max_keys
            )
            
            keys = []
            if 'Contents' in response:
                keys = [obj['Key'] for obj in response['Contents']]
            
            logger.info(f"Found {len(keys)} keys in S3 bucket")
            return keys
            
        except Exception as e:
            logger.error(f"Failed to get S3 keys: {str(e)}")
            raise
    
    def extract_identifier_from_key(self, s3_key: str) -> str:
        """
        Extract identifier from S3 key to match with Excel column
        Customize this method based on your key naming convention
        """
        # Example: extract filename without extension
        filename = s3_key.split('/')[-1]
        identifier = filename.split('.')[0]
        return identifier
    
    def get_excel_row(self, identifier: str) -> Dict[str, Any]:
        """Get matching row from Excel data"""
        try:
            matching_rows = self.excel_df[
                self.excel_df[self.excel_match_column].astype(str) == str(identifier)
            ]
            
            if matching_rows.empty:
                return {"error": f"No matching row found for identifier: {identifier}"}
            
            # Return first matching row as dictionary
            row_data = matching_rows.iloc[0].to_dict()
            
            # Convert any NaN values to None for JSON serialization
            for key, value in row_data.items():
                if pd.isna(value):
                    row_data[key] = None
            
            return row_data
            
        except Exception as e:
            logger.error(f"Failed to get Excel row for {identifier}: {str(e)}")
            return {"error": str(e)}
    
    async def query_api(self, session: aiohttp.ClientSession, identifier: str) -> Dict[str, Any]:
        """Query API asynchronously"""
        try:
            url = f"{self.api_base_url}/{identifier}"
            
            async with session.get(url, timeout=30) as response:
                if response.status == 200:
                    content = await response.text()
                    
                    # Parse XML response
                    root = ET.fromstring(content)
                    api_data = self._xml_to_dict(root)
                    
                    return api_data
                else:
                    return {"error": f"API request failed with status {response.status}"}
                    
        except Exception as e:
            logger.error(f"API query failed for {identifier}: {str(e)}")
            return {"error": str(e)}
    
    def _xml_to_dict(self, element: ET.Element) -> Dict[str, Any]:
        """Convert XML element to dictionary"""
        result = {}
        
        # Add element attributes
        if element.attrib:
            result.update(element.attrib)
        
        # Handle element text
        if element.text and element.text.strip():
            if len(element) == 0:  # No children
                return element.text.strip()
            else:
                result['text'] = element.text.strip()
        
        # Handle child elements
        for child in element:
            child_data = self._xml_to_dict(child)
            
            if child.tag in result:
                # Convert to list if multiple elements with same tag
                if not isinstance(result[child.tag], list):
                    result[child.tag] = [result[child.tag]]
                result[child.tag].append(child_data)
            else:
                result[child.tag] = child_data
        
        return result
    
    def combine_data(self, s3_key: str, excel_data: Dict[str, Any], 
                    api_data: Dict[str, Any]) -> Dict[str, Any]:
        """Combine Excel and API data into metadata"""
        metadata = {
            "s3_key": s3_key,
            "processing_timestamp": pd.Timestamp.now().isoformat(),
            "excel_data": excel_data,
            "api_data": api_data
        }
        
        # Add any custom business logic here
        # For example, extract specific fields or calculate derived values
        
        return metadata
    
    def update_s3_metadata(self, s3_key: str, metadata: Dict[str, Any]) -> bool:
        """Update S3 object metadata"""
        try:
            # Convert metadata to JSON string for storage
            metadata_str = json.dumps(metadata, default=str)
            
            # Copy object with new metadata
            copy_source = {'Bucket': self.s3_bucket_name, 'Key': s3_key}
            
            self.s3_client.copy_object(
                CopySource=copy_source,
                Bucket=self.s3_bucket_name,
                Key=s3_key,
                Metadata={
                    'processing-metadata': metadata_str
                },
                MetadataDirective='REPLACE'
            )
            
            return True
            
        except Exception as e:
            logger.error(f"Failed to update S3 metadata for {s3_key}: {str(e)}")
            return False
    
    async def process_single_key(self, session: aiohttp.ClientSession, 
                               s3_key: str) -> ProcessingResult:
        """Process a single S3 key"""
        try:
            # Extract identifier from S3 key
            identifier = self.extract_identifier_from_key(s3_key)
            
            # Get Excel data
            excel_data = self.get_excel_row(identifier)
            
            # Query API
            api_data = await self.query_api(session, identifier)
            
            # Combine data
            combined_metadata = self.combine_data(s3_key, excel_data, api_data)
            
            # Update S3 metadata
            success = self.update_s3_metadata(s3_key, combined_metadata)
            
            return ProcessingResult(
                s3_key=s3_key,
                excel_data=excel_data,
                api_data=api_data,
                combined_metadata=combined_metadata,
                success=success
            )
            
        except Exception as e:
            logger.error(f"Failed to process key {s3_key}: {str(e)}")
            return ProcessingResult(
                s3_key=s3_key,
                excel_data={},
                api_data={},
                combined_metadata={},
                success=False,
                error_message=str(e)
            )
    
    async def process_keys_async(self, s3_keys: List[str], 
                               max_concurrent: int = 10) -> List[ProcessingResult]:
        """Process multiple S3 keys asynchronously"""
        semaphore = asyncio.Semaphore(max_concurrent)
        
        async def process_with_semaphore(session, key):
            async with semaphore:
                return await self.process_single_key(session, key)
        
        async with aiohttp.ClientSession() as session:
            tasks = [process_with_semaphore(session, key) for key in s3_keys]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            # Handle exceptions
            processed_results = []
            for i, result in enumerate(results):
                if isinstance(result, Exception):
                    processed_results.append(ProcessingResult(
                        s3_key=s3_keys[i],
                        excel_data={},
                        api_data={},
                        combined_metadata={},
                        success=False,
                        error_message=str(result)
                    ))
                else:
                    processed_results.append(result)
            
            return processed_results
    
    def process_keys_sync(self, s3_keys: List[str], 
                         max_workers: int = 5) -> List[ProcessingResult]:
        """Process multiple S3 keys synchronously with threading"""
        def process_key_sync(s3_key):
            return asyncio.run(self.process_single_key_sync(s3_key))
        
        results = []
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            future_to_key = {
                executor.submit(process_key_sync, key): key 
                for key in s3_keys
            }
            
            for future in as_completed(future_to_key):
                try:
                    result = future.result()
                    results.append(result)
                except Exception as e:
                    key = future_to_key[future]
                    results.append(ProcessingResult(
                        s3_key=key,
                        excel_data={},
                        api_data={},
                        combined_metadata={},
                        success=False,
                        error_message=str(e)
                    ))
        
        return results
    
    async def process_single_key_sync(self, s3_key: str) -> ProcessingResult:
        """Synchronous version of process_single_key"""
        try:
            identifier = self.extract_identifier_from_key(s3_key)
            excel_data = self.get_excel_row(identifier)
            
            # Synchronous API call
            url = f"{self.api_base_url}/{identifier}"
            response = requests.get(url, timeout=30)
            
            if response.status_code == 200:
                root = ET.fromstring(response.text)
                api_data = self._xml_to_dict(root)
            else:
                api_data = {"error": f"API request failed with status {response.status_code}"}
            
            combined_metadata = self.combine_data(s3_key, excel_data, api_data)
            success = self.update_s3_metadata(s3_key, combined_metadata)
            
            return ProcessingResult(
                s3_key=s3_key,
                excel_data=excel_data,
                api_data=api_data,
                combined_metadata=combined_metadata,
                success=success
            )
            
        except Exception as e:
            return ProcessingResult(
                s3_key=s3_key,
                excel_data={},
                api_data={},
                combined_metadata={},
                success=False,
                error_message=str(e)
            )
    
    def run_orchestrator(self, prefix: str = '', max_keys: int = 1000, 
                        use_async: bool = True, max_concurrent: int = 10) -> List[ProcessingResult]:
        """Main orchestrator method"""
        try:
            # Get S3 keys
            s3_keys = self.get_s3_keys(prefix, max_keys)
            
            if not s3_keys:
                logger.warning("No S3 keys found")
                return []
            
            # Process keys
            if use_async:
                results = asyncio.run(self.process_keys_async(s3_keys, max_concurrent))
            else:
                results = self.process_keys_sync(s3_keys, max_concurrent)
            
            # Log summary
            successful = sum(1 for r in results if r.success)
            failed = len(results) - successful
            
            logger.info(f"Processing complete: {successful} successful, {failed} failed")
            
            return results
            
        except Exception as e:
            logger.error(f"Orchestrator failed: {str(e)}")
            raise

# Example usage
if __name__ == "__main__":
    # Configuration
    config = {
        's3_bucket_name': 'your-bucket-name',
        'excel_file_path': '/path/to/your/excel/file.xlsx',  # or 's3://bucket/path/to/file.xlsx'
        'api_base_url': 'https://api.example.com/data',
        'excel_match_column': 'file_id',  # Column name to match against S3 key identifiers
        'aws_access_key_id': 'your-access-key',  # Optional if using IAM roles
        'aws_secret_access_key': 'your-secret-key',  # Optional if using IAM roles
        'aws_region': 'us-east-1'
    }
    
    # Initialize orchestrator
    orchestrator = S3ExcelAPIOrchestrator(**config)
    
    # Run processing
    results = orchestrator.run_orchestrator(
        prefix='',  # Optional prefix to filter S3 keys
        max_keys=100,  # Maximum number of keys to process
        use_async=True,  # Use async processing for better performance
        max_concurrent=10  # Maximum concurrent operations
    )
    
    # Print results summary
    for result in results:
        if result.success:
            print(f"✓ {result.s3_key}: Successfully processed")
        else:
            print(f"✗ {result.s3_key}: Failed - {result.error_message}")